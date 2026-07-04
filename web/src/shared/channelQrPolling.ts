import { onUnmounted, reactive } from 'vue'
import * as QRCode from 'qrcode'
import { normalizeChannelQrStatus } from './channelQr'

export type ChannelQrUiStatus = 'idle' | 'loading' | 'waiting' | 'scaned' | 'confirmed' | 'error' | 'expired'

export interface ChannelQrPollingState {
  url: string
  imageUrl: string
  message: string
  id: string
  status: ChannelQrUiStatus
  failures: number
  timer: ReturnType<typeof setTimeout> | null
  domain: string
}

type MaybePromise<T> = T | Promise<T>
type MessageResolver = string | (() => string)

interface ChannelQrPollingOptions<Key extends string> {
  start: (key: Key) => Promise<object>
  poll: (key: Key, id: string) => Promise<object>
  onConfirmed?: (key: Key, state: ChannelQrPollingState) => MaybePromise<void>
  onError?: (message: string, key: Key) => void
  startErrorMessage: MessageResolver
  pollErrorMessage: MessageResolver
}

function createState(): ChannelQrPollingState {
  return { url: '', imageUrl: '', message: '', id: '', status: 'idle', failures: 0, timer: null, domain: '' }
}

function fallbackMessage(value: MessageResolver): string {
  return typeof value === 'function' ? value() : value
}

function errorMessage(error: unknown, fallback: MessageResolver): string {
  return error instanceof Error && error.message ? error.message : fallbackMessage(fallback)
}

export function useChannelQrPolling<Key extends string>(
  initialKeys: readonly Key[],
  options: ChannelQrPollingOptions<Key>,
) {
  const states = reactive<Record<string, ChannelQrPollingState>>({})

  for (const key of initialKeys) {
    states[key] = createState()
  }

  function stateFor(key: Key): ChannelQrPollingState {
    if (!states[key]) states[key] = createState()
    return states[key]
  }

  async function updateSource(state: ChannelQrPollingState, raw: string): Promise<void> {
    const value = (raw || '').trim()
    if (!value || value === state.url) return
    state.url = value
    state.imageUrl = /^data:image\//i.test(value)
      ? value
      : await QRCode.toDataURL(value, { width: 240, margin: 2, errorCorrectionLevel: 'M' })
  }

  function stop(key: Key): void {
    const state = states[key]
    if (state?.timer) {
      clearTimeout(state.timer)
      state.timer = null
    }
  }

  function reset(key: Key): ChannelQrPollingState {
    stop(key)
    const state = stateFor(key)
    Object.assign(state, createState(), { status: 'loading' })
    return state
  }

  async function applyPayload(key: Key, payload: object): Promise<void> {
    const state = stateFor(key)
    const view = normalizeChannelQrStatus(payload as Record<string, any>)
    state.id = view.qrcode || state.id
    state.message = view.error_message || view.message || ''
    state.domain = view.domain || ''
    await updateSource(state, view.qrcode_url || '')
    if (view.status === 'confirmed') {
      state.status = 'confirmed'
      await options.onConfirmed?.(key, state)
      return
    }
    if (view.status === 'expired') {
      state.status = 'expired'
      return
    }
    if (view.status === 'error') {
      state.status = 'error'
      return
    }
    state.status = view.status === 'scaned' || view.status === 'scaned_but_redirect'
      ? 'scaned'
      : (state.url ? 'waiting' : 'loading')
    poll(key)
  }

  async function start(key: Key): Promise<void> {
    const state = reset(key)
    try {
      await applyPayload(key, await options.start(key))
    } catch (error) {
      state.status = 'error'
      state.message = errorMessage(error, options.startErrorMessage)
      options.onError?.(state.message, key)
    }
  }

  function poll(key: Key): void {
    const state = states[key]
    if (!state?.id) return
    state.timer = setTimeout(async () => {
      try {
        await applyPayload(key, await options.poll(key, state.id))
        state.failures = 0
      } catch (error) {
        state.failures += 1
        if (state.failures >= 3) {
          state.status = 'error'
          state.message = errorMessage(error, options.pollErrorMessage)
          return
        }
        poll(key)
      }
    }, 3000)
  }

  onUnmounted(() => {
    Object.keys(states).forEach(key => stop(key as Key))
  })

  return {
    states: states as Record<Key, ChannelQrPollingState>,
    stateFor,
    start,
    stop,
  }
}
