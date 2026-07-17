import { parseSlashCommand } from '../domain/slash.js'
import type { SetupStatusResponse, SlashExecResponse } from '../gatewayTypes.js'
import { asCommandDispatch, rpcErrorMessage } from '../lib/rpc.js'

import type { SlashHandlerContext } from './interfaces.js'
import { renderSlashExecOutput } from './slash/output.js'
import { findSlashCommand } from './slash/registry.js'
import type { SlashRunCtx } from './slash/types.js'
import { getUiState } from './uiStore.js'

const shouldUseBackendSetupCommand = (name: string, arg: string): boolean => {
  const command = `${name} ${arg}`.trim().toLowerCase()

  return command === 'auth'
    || command.startsWith('auth ')
    || command === 'doctor'
    || command === 'gateway'
    || command.startsWith('gateway ')
    || command === 'model set'
    || command.startsWith('model set ')
    || command === 'model configure'
    || command.startsWith('model configure ')
}

export function createSlashHandler(ctx: SlashHandlerContext): (cmd: string) => boolean {
  const { gw } = ctx.gateway
  const { catalog } = ctx.local
  const { send, sys } = ctx.transcript

  const handler = (cmd: string): boolean => {
    const flight = ++ctx.slashFlightRef.current
    const ui = getUiState()
    const sid = ui.sid
    const parsed = parseSlashCommand(cmd)
    const argTail = parsed.arg ? ` ${parsed.arg}` : ''

    const stale = () => flight !== ctx.slashFlightRef.current || getUiState().sid !== sid

    const guarded =
      <T>(fn: (r: T) => void) =>
      (r: null | T): void => {
        if (!stale() && r) {
          fn(r)
        }
      }

    const guardedErr = (e: unknown) => {
      if (!stale()) {
        sys(`error: ${rpcErrorMessage(e)}`)
      }
    }

    const runCtx: SlashRunCtx = { ...ctx, flight, guarded, guardedErr, sid, stale, ui }

    if (shouldUseBackendSetupCommand(parsed.name, parsed.arg)) {
      const backendCommand = `${parsed.name}${argTail}`

      gw.request<SlashExecResponse>('slash.exec', { command: backendCommand, session_id: sid })
        .then(r => {
          if (!stale()) {
            renderSlashExecOutput(ctx.transcript, r, `/${parsed.name}: no output`, parsed.name[0]!.toUpperCase() + parsed.name.slice(1))

            const normalized = backendCommand.trim().toLowerCase()

            const isModelSetup =
              normalized === 'model set'
              || normalized.startsWith('model set ')
              || normalized === 'model configure'
              || normalized.startsWith('model configure ')

            if (
              isModelSetup
              && String(r?.output ?? '').includes('模型配置已写入')
              && !getUiState().sid
              && getUiState().status === 'setup required'
            ) {
              // 模型配置成功后立即复查 setup 状态，避免用户必须重启 TUI 才能进入会话。
              ctx.gateway.rpc<SetupStatusResponse>('setup.status', {})
                .then(setup => {
                  if (!stale() && setup?.provider_configured !== false) {
                    ctx.session.newSession()
                  }
                })
                .catch(guardedErr)
            }
          }
        })
        .catch(guardedErr)

      return true
    }

    const found = findSlashCommand(parsed.name)

    if (found) {
      found.run(parsed.arg, runCtx, cmd)

      return true
    }

    if (catalog?.canon) {
      const needle = `/${parsed.name}`.toLowerCase()
      const exact = Object.entries(catalog.canon).find(([alias]) => alias.toLowerCase() === needle)?.[1]

      if (exact) {
        if (exact.toLowerCase() !== needle) {
          return handler(`${exact}${argTail}`)
        }
      } else {
        const matches = [
          ...new Set(
            Object.entries(catalog.canon)
              .filter(([alias]) => alias.startsWith(needle))
              .map(([, canon]) => canon)
          )
        ]

        if (matches.length === 1 && matches[0]!.toLowerCase() !== needle) {
          return handler(`${matches[0]}${argTail}`)
        }

        if (matches.length > 1) {
          sys(`ambiguous command: ${matches.slice(0, 6).join(', ')}${matches.length > 6 ? ', …' : ''}`)

          return true
        }

        sys(`unknown command: /${parsed.name} — try /help`)

        return true
      }
    }

    const handleDispatch = (raw: unknown): void => {
      const d = asCommandDispatch(raw)

      if (!d) {
        return sys('error: invalid response: command.dispatch')
      }

      if (d.type === 'exec') {
        return sys(d.output || '(no output)')
      }

      if (d.type === 'alias') {
        return void handler(`/${d.target}${argTail}`)
      }

      if (d.type === 'skill') {
        sys(`⚡ loading skill: ${d.name}`)

        return d.message?.trim() ? send(d.message) : sys(`/${parsed.name}: skill payload missing message`)
      }

      if (d.type === 'send') {
        if (d.notice?.trim()) {
          sys(d.notice)
        }

        return d.message?.trim() ? send(d.message) : sys(`/${parsed.name}: empty message`)
      }

      if (d.type === 'prefill') {
        // /undo returns prefill: drop the backed-up message text into the
        // composer so the user can edit and resubmit.
        if (d.notice?.trim()) {
          sys(d.notice)
        }

        if (d.message) {
          ctx.composer.setInput(d.message)
        }
      }
    }

    gw.request<SlashExecResponse>('slash.exec', { command: cmd.slice(1), session_id: sid })
      .then(r => {
        if (stale()) {
          return
        }

        if (asCommandDispatch(r)) {
          return handleDispatch(r)
        }

        renderSlashExecOutput(ctx.transcript, r, `/${parsed.name}: no output`, parsed.name[0]!.toUpperCase() + parsed.name.slice(1))
      })
      .catch(() => {
        gw.request('command.dispatch', { arg: parsed.arg, name: parsed.name, session_id: sid })
          .then((raw: unknown) => {
            if (stale()) {
              return
            }

            handleDispatch(raw)
          })
          .catch(guardedErr)
      })

    return true
  }

  return handler
}
