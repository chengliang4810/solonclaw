import type { ChildProcess } from 'node:child_process'
import { EventEmitter } from 'node:events'
import type { createInterface } from 'node:readline'

import { WebSocket as WsWebSocket } from 'ws'

import type { GatewayEvent } from './gatewayTypes.js'
import { CircularBuffer } from './lib/circularBuffer.js'
import { recordParentLifecycle } from './lib/parentLog.js'

// Node.js 20 没有全局 WebSocket，用 ws 模块补全
// 懒解析：每次调用时检查全局 WebSocket（测试会动态 mock/delete）
const _wsModuleFallback: typeof WebSocket = WsWebSocket as unknown as typeof WebSocket

function resolveWsCtor(): typeof WebSocket {
  return typeof WebSocket !== 'undefined' ? WebSocket : _wsModuleFallback
}

const MAX_GATEWAY_LOG_LINES = 200
const MAX_LOG_LINE_BYTES = 4096
const MAX_BUFFERED_EVENTS = 2000
const MAX_LOG_PREVIEW = 240
const STARTUP_TIMEOUT_MS = Math.max(5000, parseInt(process.env.SOLONCLAW_TUI_STARTUP_TIMEOUT_MS ?? '15000', 10) || 15000)

const STARTUP_RETRY_COOLDOWN_MS = Math.max(
  250,
  parseInt(process.env.SOLONCLAW_TUI_STARTUP_RETRY_COOLDOWN_MS ?? '3000', 10) || 3000
)

const REQUEST_TIMEOUT_MS = Math.max(30000, parseInt(process.env.SOLONCLAW_TUI_RPC_TIMEOUT_MS ?? '120000', 10) || 120000)
const DEFAULT_SERVER_URL = 'http://127.0.0.1:8080'
const PROTOCOL_VERSION = 1
const WS_CONNECTING = 0
const WS_OPEN = 1
const WS_CLOSING = 2
const WS_CLOSED = 3

const truncateLine = (line: string) =>
  line.length > MAX_LOG_LINE_BYTES ? `${line.slice(0, MAX_LOG_LINE_BYTES)}… [truncated ${line.length} bytes]` : line

const describeChild = (proc: ChildProcess | null) => {
  if (!proc) {
    return 'pid=none'
  }

  return `pid=${proc.pid ?? 'unknown'} killed=${proc.killed} exitCode=${proc.exitCode ?? 'null'} signal=${proc.signalCode ?? 'null'}`
}

const resolveGatewayAttachUrl = () => {
  const raw = process.env.SOLONCLAW_TUI_GATEWAY_URL?.trim()

  return raw ? raw : null
}

const resolveSidecarUrl = () => {
  const raw = process.env.SOLONCLAW_TUI_SIDECAR_URL?.trim()

  return raw ? raw : null
}

const normalizeServerUrl = (value = DEFAULT_SERVER_URL) => {
  let result = value.trim() || DEFAULT_SERVER_URL

  while (result.endsWith('/') && result.length > 1) {
    result = result.slice(0, -1)
  }

  return result
}

const resolveServerUrl = () => normalizeServerUrl(process.env.SOLONCLAW_SERVER_URL ?? DEFAULT_SERVER_URL)

const handshakeUrl = () => `${resolveServerUrl()}/api/tui/handshake`

const resolveDashboardToken = () =>
  process.env.SOLONCLAW_DASHBOARD_ACCESS_TOKEN?.trim() || process.env.SOLONCLAW_DASHBOARD_TOKEN?.trim() || ''

type HandshakeResponse = {
  app?: string
  features?: string[]
  mode?: string
  protocol_version?: number
  ws_url?: string
}

const fetchHandshake = async (): Promise<HandshakeResponse> => {
  const url = handshakeUrl()
  const dashboardToken = resolveDashboardToken()

  const response = dashboardToken
    ? await fetch(url, { headers: { Authorization: `Bearer ${dashboardToken}` } })
    : await fetch(url)

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }

  return (await response.json()) as HandshakeResponse
}

const asGatewayEvent = (value: unknown): GatewayEvent | null =>
  value && typeof value === 'object' && !Array.isArray(value) && typeof (value as { type?: unknown }).type === 'string'
    ? (value as GatewayEvent)
    : null

// Hoisted decoder: attach mode can drive high-frequency binary frames
// (tool deltas, reasoning streams) and constructing a fresh TextDecoder
// per message creates avoidable GC pressure. One module-level instance
// is fine because UTF-8 is stateless and we always pass entire frames.
const _wireDecoder = new TextDecoder()

const asWireText = (raw: unknown): string | null => {
  if (typeof raw === 'string') {
    return raw
  }

  if (raw instanceof ArrayBuffer) {
    return _wireDecoder.decode(raw)
  }

  if (ArrayBuffer.isView(raw)) {
    return _wireDecoder.decode(raw)
  }

  return null
}

// Matches `<scheme>://user:pass@host…` style user-info segments in
// otherwise-malformed URLs that the WHATWG `URL` parser can't accept.
// Used by the `redactUrl` fallback so embedded credentials are
// scrubbed from log lines even when the URL is unparseable.
const _USERINFO_FALLBACK_RE = /^([a-z][a-z0-9+.-]*:\/\/)[^/?#@]*@/i

// Connection URLs (gateway, sidecar) often carry bearer tokens in the query
// string. We surface them in user-facing log lines and the
// `gateway.start_timeout` payload, so always strip the query string and any
// embedded user-info before logging.
const redactUrl = (raw: string): string => {
  if (!raw) {
    return raw
  }

  try {
    const url = new URL(raw)
    const userInfo = url.username || url.password ? '***@' : ''
    const query = url.search ? '?***' : ''

    return `${url.protocol}//${userInfo}${url.host}${url.pathname}${query}`
  } catch {
    // WHATWG URL rejected the input. Best-effort: strip an embedded
    // `user:pass@` segment AND the query string so a malformed token
    // bearer can never escape into the log tail.
    const noUserInfo = raw.replace(_USERINFO_FALLBACK_RE, '$1***@')
    const queryIdx = noUserInfo.indexOf('?')

    return queryIdx >= 0 ? `${noUserInfo.slice(0, queryIdx)}?***` : noUserInfo
  }
}

interface Pending {
  id: string
  method: string
  reject: (e: Error) => void
  resolve: (v: unknown) => void
  timeout: ReturnType<typeof setTimeout>
}

export class GatewayClient extends EventEmitter {
  private proc: ChildProcess | null = null
  private ws: WebSocket | null = null
  private wsConnectPromise: Promise<void> | null = null
  private startupPromise: Promise<void> | null = null
  private sidecarWs: WebSocket | null = null
  private attachUrl: null | string = null
  private sidecarUrl: null | string = null
  private reqId = 0
  private logs = new CircularBuffer<string>(MAX_GATEWAY_LOG_LINES)
  private pending = new Map<string, Pending>()
  private bufferedEvents = new CircularBuffer<GatewayEvent>(MAX_BUFFERED_EVENTS)
  private pendingExit: number | null | undefined
  private requestQueue: Promise<void> = Promise.resolve()
  private ready = false
  private readyTimer: ReturnType<typeof setTimeout> | null = null
  private subscribed = false
  private stdoutRl: ReturnType<typeof createInterface> | null = null
  private stderrRl: ReturnType<typeof createInterface> | null = null
  private lastStartupFailureAt = 0
  private backendUnavailable = false

  constructor() {
    super()
    // useInput / createGatewayEventHandler can legitimately attach many
    // listeners. Default 10-cap triggers spurious warnings.
    this.setMaxListeners(0)
  }

  private publish(ev: GatewayEvent) {
    if (ev.type === 'gateway.ready') {
      this.lifecycle(`[startup] publish gateway.ready subscribed=${this.subscribed}`)
    }

    if (ev.type === 'gateway.ready') {
      this.ready = true
      this.lastStartupFailureAt = 0
      this.backendUnavailable = false

      if (this.readyTimer) {
        clearTimeout(this.readyTimer)
        this.readyTimer = null
      }
    }

    if (this.subscribed) {
      return void this.emit('event', ev)
    }

    this.bufferedEvents.push(ev)
  }

  private clearReadyTimer() {
    if (this.readyTimer) {
      clearTimeout(this.readyTimer)
      this.readyTimer = null
    }
  }

  private closeSidecarSocket() {
    try {
      this.sidecarWs?.close()
    } catch {
      // best effort
    } finally {
      this.sidecarWs = null
    }
  }

  private closeGatewaySocket() {
    // Null the active reference BEFORE invoking close(): real WebSocket
    // implementations dispatch the 'close' event after a microtask hop,
    // so by the time the handler runs `this.ws` should already be null
    // and the identity guard will correctly classify the close as
    // belonging to a discarded socket. (Test fakes emit synchronously,
    // so doing the swap up front is also what makes the identity guard
    // match real timing in tests.)
    const ws = this.ws
    this.ws = null
    this.wsConnectPromise = null

    try {
      ws?.close()
    } catch {
      // best effort
    }
  }

  private resetStartupState() {
    // Reject any in-flight RPCs left over from the previous transport
    // before we swap. Otherwise the old transport's stale exit/close
    // handlers (now identity-gated to ignore unrelated transports)
    // never fire `rejectPending`, leaving callers hanging on promises
    // attached to a discarded child / socket.
    this.rejectPending(new Error('gateway restarting'))
    this.ready = false
    this.bufferedEvents.clear()
    this.pendingExit = undefined
    this.stdoutRl?.close()
    this.stderrRl?.close()
    this.stdoutRl = null
    this.stderrRl = null
    this.startupPromise = null
    this.clearReadyTimer()
  }

  private startReadyTimer(transport: string, target: string) {
    this.readyTimer = setTimeout(() => {
      if (this.ready) {
        return
      }

      // Append the most recent gateway stderr/log lines to the timeout
      // event so users can tell apart "wrong python", "missing dep",
      // and "config parse failure" from one glance instead of having
      // to dig through `/logs`.  Capped to keep the activity feed
      // readable on slow boots.
      const stderrTail = this.getLogTail(20)

      this.lifecycle(`[startup] timed out waiting for gateway.ready (transport=${transport}, target=${target})`)
      this.publish({
        type: 'gateway.start_timeout',
        payload: { cwd: target, python: transport, stderr_tail: stderrTail }
      })
    }, STARTUP_TIMEOUT_MS)
  }

  private handleTransportExit(code: null | number, reason?: string) {
    this.clearReadyTimer()
    this.lastStartupFailureAt = Date.now()
    this.closeSidecarSocket()
    this.lifecycle(`[lifecycle] transport exit code=${code ?? 'null'} reason=${reason ?? 'none'}`)
    this.rejectPending(new Error(reason || `gateway exited${code === null ? '' : ` (${code})`}`))

    if (this.subscribed) {
      this.emit('exit', code)
    } else {
      this.pendingExit = code
    }
  }

  private connectSidecarMirror() {
    this.closeSidecarSocket()

    if (!this.sidecarUrl) {
      return
    }

    if (typeof resolveWsCtor() === 'undefined') {
      this.pushLog(`[sidecar] WebSocket unavailable; skipping mirror to ${redactUrl(this.sidecarUrl)}`)

      return
    }

    try {
      const ws = new (resolveWsCtor())(this.sidecarUrl)

      this.sidecarWs = ws
      ws.addEventListener('close', () => {
        if (this.sidecarWs === ws) {
          this.sidecarWs = null
        }
      })
      ws.addEventListener('error', () => {
        this.pushLog('[sidecar] mirror connection error')
      })
    } catch (err) {
      this.pushLog(`[sidecar] failed to connect ${redactUrl(this.sidecarUrl)} (constructor error)`)
      this.sidecarWs = null
    }
  }

  private mirrorEventToSidecar(rawFrame: string) {
    const ws = this.sidecarWs

    if (!ws || ws.readyState !== WS_OPEN) {
      return
    }

    try {
      ws.send(rawFrame)
    } catch {
      // best effort
    }
  }

  private sendWire(frame: Record<string, unknown>) {
    if (!this.ws || this.ws.readyState !== WS_OPEN) {
      return
    }

    try {
      this.ws.send(JSON.stringify(frame))
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err)

      this.pushLog(`[protocol] failed to send websocket frame: ${message}`)
    }
  }

  private handleWebSocketFrame(raw: unknown) {
    const text = asWireText(raw)

    if (!text) {
      return
    }

    try {
      const frame = JSON.parse(text) as Record<string, unknown>

      if (frame.method === 'event') {
        this.mirrorEventToSidecar(text)
      }

      this.dispatch(frame)
    } catch {
      const preview = text.trim().slice(0, MAX_LOG_PREVIEW) || '(empty frame)'

      this.pushLog(`[protocol] malformed websocket frame: ${preview}`)
      this.publish({ type: 'gateway.protocol_error', payload: { preview } })
    }
  }

  private startAttachedGateway(attachUrl: string): Promise<void> {
    const safeAttachUrl = redactUrl(attachUrl)
    this.clearReadyTimer()
    this.startReadyTimer('websocket', safeAttachUrl)

    if (typeof resolveWsCtor() === 'undefined') {
      const line = `[startup] WebSocket API unavailable; cannot attach to ${safeAttachUrl}`

      this.pushLog(line)
      this.publish({ type: 'gateway.stderr', payload: { line } })
      this.handleTransportExit(1, 'gateway websocket unavailable')

      return Promise.reject(new Error('gateway websocket unavailable'))
    }

    try {
      const ws = new (resolveWsCtor())(attachUrl)
      let settled = false

      this.ws = ws

      const connectPromise = new Promise<void>((resolve, reject) => {
        ws.addEventListener(
          'open',
          () => {
            if (!settled) {
              settled = true
              resolve()
            }

            this.connectSidecarMirror()
            this.sendWire({ payload: { protocol_version: PROTOCOL_VERSION }, type: 'client.hello' })
            this.publish({ type: 'gateway.ready', payload: {} })
          },
          { once: true }
        )

        ws.addEventListener(
          'error',
          () => {
            if (!settled) {
              this.pushLog('[startup] gateway websocket connect error')
              settled = true
              reject(new Error('gateway websocket connection failed'))
            }
          },
          { once: true }
        )
        ws.addEventListener(
          'close',
          ev => {
            if (!settled) {
              settled = true
              reject(new Error(`gateway websocket closed (${ev.code}) during connect`))
            }
          },
          { once: true }
        )
      })

      // The connect promise is only awaited by RPCs that arrive while
      // the socket is still connecting. If no request races the open
      // (or a teardown drops the reference before anyone observes it),
      // a connect-error / early-close rejection would surface as an
      // unhandled promise rejection in Node. Attach a no-op handler to
      // ensure the rejection is always observed.
      connectPromise.catch(() => {})
      this.wsConnectPromise = connectPromise

      ws.addEventListener('message', ev => this.handleWebSocketFrame(ev.data))
      ws.addEventListener('close', ev => {
        // Skip close events from sockets that have already been
        // replaced — start() / closeGatewaySocket() can swap `this.ws`
        // before an in-flight close lands, and we must not clear the
        // new ready timer or reject the new pending requests on behalf
        // of a stale socket.
        if (this.ws !== ws) {
          this.pushLog(`[lifecycle] stale websocket close ignored code=${ev.code}`)

          return
        }

        this.pushLog(`[lifecycle] websocket close code=${ev.code}`)
        this.ws = null
        this.wsConnectPromise = null
        this.handleTransportExit(ev.code, `gateway websocket closed${ev.code ? ` (${ev.code})` : ''}`)
      })
      ws.addEventListener('error', () => {
        const line = '[gateway] websocket transport error'

        this.pushLog(line)
        this.publish({ type: 'gateway.stderr', payload: { line } })
      })

      return connectPromise
    } catch (err) {
      const rawMessage = err instanceof Error ? err.message : String(err)
      const safeMessage = redactUrl(rawMessage)
      const line = `[startup] failed to connect websocket gateway ${safeAttachUrl} (constructor error: ${safeMessage})`

      this.pushLog(line)
      this.publish({ type: 'gateway.stderr', payload: { line } })
      this.handleTransportExit(1, 'gateway websocket startup failed')

      return Promise.reject(err instanceof Error ? err : new Error(String(err)))
    }
  }

  private startSolonBackendGateway(): Promise<void> {
    const url = handshakeUrl()

    this.startReadyTimer('solon-backend', url)
    this.pushLog(`[startup] handshake ${url}`)

    return fetchHandshake()
      .then(handshake => {
        const wsUrl = handshake.ws_url?.trim()

        if (!wsUrl) {
          throw new Error('handshake response missing ws_url')
        }

        return this.startAttachedGateway(wsUrl)
      })
      .catch(err => {
        const message = err instanceof Error ? err.message : String(err)
        const line = `[startup] backend handshake failed: ${message}`

        this.backendUnavailable = true
        this.pushLog(line)
        this.publish({ type: 'gateway.stderr', payload: { line } })
        this.publish({ type: 'error', payload: { message: `无法连接后端服务: ${message}` } })
        this.handleTransportExit(1, 'backend handshake failed')
        throw err instanceof Error ? err : new Error(String(err))
      })
  }

  start() {
    const attachUrl = resolveGatewayAttachUrl()
    const sidecarUrl = resolveSidecarUrl()

    this.backendUnavailable = false
    this.attachUrl = attachUrl
    this.sidecarUrl = sidecarUrl
    this.resetStartupState()

    if (this.proc && !this.proc.killed && this.proc.exitCode === null) {
      this.lifecycle(`[lifecycle] replacing live gateway child ${describeChild(this.proc)}`)
      this.proc.kill()
    }

    this.proc = null
    this.closeGatewaySocket()
    this.closeSidecarSocket()

    const startup = attachUrl ? this.startAttachedGateway(attachUrl) : this.startSolonBackendGateway()

    const tracked = startup.finally(() => {
      if (this.startupPromise === tracked) {
        this.startupPromise = null
      }
    })

    tracked.catch(() => {})
    this.startupPromise = tracked
  }

  private dispatch(msg: Record<string, unknown>) {
    const id = msg.id as string | undefined
    const p = id ? this.pending.get(id) : undefined

    if (p) {
      this.settle(p, msg.error ? this.toError(msg.error) : null, msg.result)

      return
    }

    if (msg.method === 'event') {
      const ev = asGatewayEvent(msg.params)

      if (ev) {
        this.publish(ev)
      }
    }
  }

  private toError(raw: unknown): Error {
    const err = raw as { message?: unknown } | null | undefined

    return new Error(typeof err?.message === 'string' ? err.message : 'request failed')
  }

  private settle(p: Pending, err: Error | null, result: unknown) {
    clearTimeout(p.timeout)
    this.pending.delete(p.id)

    if (err) {
      p.reject(err)
    } else {
      p.resolve(result)
    }
  }

  private pushLog(line: string) {
    this.logs.push(truncateLine(line))
  }

  // Death-explaining breadcrumbs (spawn / exit / kill / replace) — kept in the
  // in-memory tail for /logs AND persisted to the gateway crash log so the
  // reason survives a parent exit and lands next to the child's SIGTERM panic.
  private lifecycle(line: string) {
    this.pushLog(line)
    recordParentLifecycle(line)
  }

  private rejectPending(err: Error) {
    for (const p of this.pending.values()) {
      clearTimeout(p.timeout)
      p.reject(err)
    }

    this.pending.clear()
  }

  // Arrow class-field — stable identity, so `setTimeout(this.onTimeout, …, id)`
  // doesn't allocate a bound function per request.
  private onTimeout = (id: string) => {
    const p = this.pending.get(id)

    if (p) {
      this.pending.delete(id)
      p.reject(new Error(`timeout: ${p.method}`))
    }
  }

  drain() {
    this.subscribed = true
    this.lifecycle(`[startup] drain buffered=${this.bufferedEvents.tail().length}`)

    for (const ev of this.bufferedEvents.drain()) {
      this.emit('event', ev)
    }

    if (this.pendingExit !== undefined) {
      const code = this.pendingExit

      this.pendingExit = undefined
      this.emit('exit', code)
    }
  }

  getLogTail(limit = 20): string {
    return this.logs.tail(Math.max(1, limit)).join('\n')
  }

  private async ensureAttachedWebSocket(method: string): Promise<WebSocket> {
    if (!this.startupPromise && (!this.ws || this.ws.readyState === WS_CLOSED || this.ws.readyState === WS_CLOSING)) {
      if (this.backendUnavailable || this.lastStartupFailureAt && Date.now() - this.lastStartupFailureAt < STARTUP_RETRY_COOLDOWN_MS) {
        throw new Error(`gateway not connected: ${method}`)
      }

      this.start()
    }

    if ((!this.ws || this.ws.readyState === WS_CONNECTING) && this.startupPromise) {
      try {
        await this.startupPromise
      } catch (err) {
        throw err instanceof Error ? err : new Error(String(err))
      }
    } else if (this.ws?.readyState === WS_CONNECTING) {
      try {
        await this.wsConnectPromise
      } catch (err) {
        throw err instanceof Error ? err : new Error(String(err))
      }
    }

    if (!this.ws || this.ws.readyState !== WS_OPEN) {
      throw new Error(`gateway not connected: ${method}`)
    }

    return this.ws
  }

  private requestOverWebSocket<T = unknown>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    return this.ensureAttachedWebSocket(method).then(
      ws =>
        new Promise<T>((resolve, reject) => {
          const id = `r${++this.reqId}`
          const timeout = setTimeout(this.onTimeout, REQUEST_TIMEOUT_MS, id)

          timeout.unref?.()
          this.pending.set(id, {
            id,
            method,
            reject,
            resolve: v => resolve(v as T),
            timeout
          })

          try {
            ws.send(JSON.stringify({ id, jsonrpc: '2.0', method, params }))
          } catch (e) {
            const pending = this.pending.get(id)

            if (pending) {
              clearTimeout(pending.timeout)
              this.pending.delete(id)
            }

            reject(e instanceof Error ? e : new Error(String(e)))
          }
        })
    )
  }

  private enqueueRequest<T = unknown>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    const request = this.requestQueue.then(
      () => this.requestOverWebSocket<T>(method, params),
      () => this.requestOverWebSocket<T>(method, params)
    )

    this.requestQueue = request.then(
      () => undefined,
      () => undefined
    )

    return request
  }

  request<T = unknown>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    const attachUrl = resolveGatewayAttachUrl()

    if (attachUrl && this.attachUrl !== attachUrl) {
      // The env var rotated at runtime — restart the transport so
      // switching transport modes tears down the old WebSocket before
      // new requests are issued.
      this.rejectPending(new Error('gateway attach url changed'))
      this.start()
    }

    return this.enqueueRequest<T>(method, params)
  }

  kill(reason = 'requested') {
    const proc = this.proc
    const killed = proc?.kill()

    this.lifecycle(`[lifecycle] GatewayClient.kill reason=${reason} ${describeChild(proc)} killResult=${killed ?? 'none'}`)
    this.closeGatewaySocket()
    this.closeSidecarSocket()
    this.clearReadyTimer()
    // The ws 'close' handler is identity-gated on `this.ws === ws`
    // and we just nulled `this.ws`, so it will short-circuit and
    // skip handleTransportExit. Reject pending RPCs explicitly so
    // attach-mode promises do not hang after an intentional kill.
    this.rejectPending(new Error('gateway closed'))
  }
}
