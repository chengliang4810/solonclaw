import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { GatewayClient } from '../gatewayClient.js'

interface ListenerEntry {
  callback: (event: any) => void
  once: boolean
}

class FakeWebSocket {
  static CONNECTING = 0
  static OPEN = 1
  static CLOSING = 2
  static CLOSED = 3
  static instances: FakeWebSocket[] = []

  readyState = FakeWebSocket.CONNECTING
  sent: string[] = []
  readonly url: string
  private listeners = new Map<string, ListenerEntry[]>()

  constructor(url: string) {
    this.url = url
    FakeWebSocket.instances.push(this)
  }

  static reset() {
    FakeWebSocket.instances = []
  }

  addEventListener(type: string, callback: (event: any) => void, options?: unknown) {
    const once =
      typeof options === 'object' &&
      options !== null &&
      'once' in options &&
      Boolean((options as { once?: unknown }).once)

    const entries = this.listeners.get(type) ?? []

    entries.push({ callback, once })
    this.listeners.set(type, entries)
  }

  removeEventListener(type: string, callback: (event: any) => void) {
    const entries = this.listeners.get(type)

    if (!entries) {
      return
    }

    this.listeners.set(
      type,
      entries.filter(entry => entry.callback !== callback)
    )
  }

  send(payload: string) {
    if (this.readyState !== FakeWebSocket.OPEN) {
      throw new Error('socket not open')
    }

    this.sent.push(payload)
  }

  close(code = 1000) {
    if (this.readyState === FakeWebSocket.CLOSED) {
      return
    }

    this.readyState = FakeWebSocket.CLOSED
    this.emit('close', { code })
  }

  open() {
    this.readyState = FakeWebSocket.OPEN
    this.emit('open', {})
  }

  message(data: string) {
    this.emit('message', { data })
  }

  private emit(type: string, event: any) {
    const entries = [...(this.listeners.get(type) ?? [])]

    for (const entry of entries) {
      entry.callback(event)

      if (entry.once) {
        this.removeEventListener(type, entry.callback)
      }
    }
  }
}

const findRpcFrame = (socket: FakeWebSocket, method: string) =>
  socket.sent.find(frame => frame.includes(`"method":"${method}"`))

describe('GatewayClient solonclaw bridge', () => {
  const originalFetch = globalThis.fetch
  const originalWebSocket = globalThis.WebSocket
  let originalGatewayUrl: string | undefined
  let originalSidecarUrl: string | undefined
  let originalDashboardToken: string | undefined
  let originalDashboardAccessToken: string | undefined
  const originalServerUrl = process.env.SOLONCLAW_SERVER_URL

  beforeEach(() => {
    originalGatewayUrl = process.env.SOLONCLAW_TUI_GATEWAY_URL
    originalSidecarUrl = process.env.SOLONCLAW_TUI_SIDECAR_URL
    originalDashboardToken = process.env.SOLONCLAW_DASHBOARD_TOKEN
    originalDashboardAccessToken = process.env.SOLONCLAW_DASHBOARD_ACCESS_TOKEN
    FakeWebSocket.reset()
    process.env.SOLONCLAW_SERVER_URL = 'http://127.0.0.1:8080'
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        json: async () => ({ protocol_version: 1, ws_url: 'ws://127.0.0.1:18080/ws/tui' }),
        ok: true,
        status: 200
      }))
    )
    ;(globalThis as { WebSocket?: unknown }).WebSocket = FakeWebSocket as unknown as typeof WebSocket
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    FakeWebSocket.reset()

    if (originalGatewayUrl === undefined) {
      delete process.env.SOLONCLAW_TUI_GATEWAY_URL
    } else {
      process.env.SOLONCLAW_TUI_GATEWAY_URL = originalGatewayUrl
    }

    if (originalSidecarUrl === undefined) {
      delete process.env.SOLONCLAW_TUI_SIDECAR_URL
    } else {
      process.env.SOLONCLAW_TUI_SIDECAR_URL = originalSidecarUrl
    }

    if (originalDashboardToken === undefined) {
      delete process.env.SOLONCLAW_DASHBOARD_TOKEN
    } else {
      process.env.SOLONCLAW_DASHBOARD_TOKEN = originalDashboardToken
    }

    if (originalDashboardAccessToken === undefined) {
      delete process.env.SOLONCLAW_DASHBOARD_ACCESS_TOKEN
    } else {
      process.env.SOLONCLAW_DASHBOARD_ACCESS_TOKEN = originalDashboardAccessToken
    }

    if (originalFetch) {
      globalThis.fetch = originalFetch
    }

    if (originalWebSocket) {
      globalThis.WebSocket = originalWebSocket
    } else {
      delete (globalThis as { WebSocket?: unknown }).WebSocket
    }

    if (originalServerUrl === undefined) {
      delete process.env.SOLONCLAW_SERVER_URL
    } else {
      process.env.SOLONCLAW_SERVER_URL = originalServerUrl
    }
  })

  it('connects through the handshake endpoint and emits gateway.ready', async () => {
    const gw = new GatewayClient()
    const events: string[] = []

    gw.on('event', event => events.push(event.type))
    gw.start()

    await vi.waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1))
    FakeWebSocket.instances[0]!.open()
    gw.drain()

    expect(globalThis.fetch).toHaveBeenCalledWith('http://127.0.0.1:8080/api/tui/handshake')
    expect(FakeWebSocket.instances[0]!.url).toBe('ws://127.0.0.1:18080/ws/tui')
    expect(FakeWebSocket.instances[0]!.sent[0]).toContain('client.hello')
    expect(events).toContain('gateway.ready')
  })

  it('sends dashboard token during handshake and redacts websocket token from logs', async () => {
    process.env.SOLONCLAW_DASHBOARD_TOKEN = 'hunter2'
    vi.mocked(globalThis.fetch).mockImplementationOnce(async (_url, init) => {
      expect(init).toEqual({ headers: { Authorization: 'Bearer hunter2' } })

      return {
        json: async () => ({ protocol_version: 1, ws_url: 'ws://127.0.0.1:18080/ws/tui?token=hunter2' }),
        ok: true,
        status: 200
      } as Response
    })
    const gw = new GatewayClient()

    gw.start()

    await vi.waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1))
    FakeWebSocket.instances[0]!.open()

    expect(FakeWebSocket.instances[0]!.url).toBe('ws://127.0.0.1:18080/ws/tui?token=hunter2')
    expect(gw.getLogTail(20)).not.toContain('hunter2')
    gw.kill()
  })

  it('waits for backend handshake before sending early RPC requests', async () => {
    let resolveHandshake!: (value: unknown) => void

    const slowHandshake = new Promise(resolve => {
      resolveHandshake = resolve
    })

    vi.mocked(globalThis.fetch).mockReturnValueOnce(slowHandshake as Promise<Response>)

    const gw = new GatewayClient()
    const req = gw.request<{ ok: boolean }>('config.get', { key: 'full' })

    expect(FakeWebSocket.instances).toHaveLength(0)

    resolveHandshake({
      json: async () => ({ protocol_version: 1, ws_url: 'ws://127.0.0.1:18080/ws/tui' }),
      ok: true,
      status: 200
    })

    await vi.waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1))

    const socket = FakeWebSocket.instances[0]!

    expect(socket.sent).toHaveLength(0)
    socket.open()
    await vi.waitFor(() => expect(findRpcFrame(socket, 'config.get')).toBeTruthy())

    const frame = JSON.parse(findRpcFrame(socket, 'config.get') ?? '{}') as { id: string; method: string }

    expect(frame.method).toBe('config.get')
    socket.message(JSON.stringify({ id: frame.id, jsonrpc: '2.0', result: { ok: true } }))

    await expect(req).resolves.toEqual({ ok: true })
    gw.kill()
  })

  it('sends JSON-RPC prompt.submit and publishes backend event envelopes', async () => {
    const gw = new GatewayClient()
    const events: Array<{ payload?: unknown; type: string }> = []

    gw.on('event', event => events.push(event))
    gw.start()
    await vi.waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1))

    const socket = FakeWebSocket.instances[0]!

    socket.open()
    gw.drain()

    const submitted = gw.request('prompt.submit', { session_id: 's1', text: '你好' })
    await vi.waitFor(() => expect(socket.sent.some(frame => frame.includes('"method":"prompt.submit"'))).toBe(true))
    const request = JSON.parse(socket.sent.at(-1) ?? '{}') as { id: string; method: string; params: unknown }

    expect(request).toMatchObject({
      jsonrpc: '2.0',
      method: 'prompt.submit',
      params: { session_id: 's1', text: '你好' }
    })

    socket.message(JSON.stringify({ jsonrpc: '2.0', method: 'event', params: { session_id: 's1', type: 'message.start' } }))
    socket.message(
      JSON.stringify({
        jsonrpc: '2.0',
        method: 'event',
        params: { payload: { text: '第一段' }, session_id: 's1', type: 'message.delta' }
      })
    )
    socket.message(
      JSON.stringify({
        jsonrpc: '2.0',
        method: 'event',
        params: { payload: { text: '第二段' }, session_id: 's1', type: 'message.delta' }
      })
    )
    socket.message(
      JSON.stringify({
        jsonrpc: '2.0',
        method: 'event',
        params: { payload: { text: '第一段第二段' }, session_id: 's1', type: 'message.complete' }
      })
    )
    socket.message(JSON.stringify({ id: request.id, jsonrpc: '2.0', result: { ok: true } }))

    await expect(submitted).resolves.toEqual({ ok: true })
    expect(events.map(event => event.type)).toEqual(['gateway.ready', 'message.start', 'message.delta', 'message.delta', 'message.complete'])
    expect(events.at(-1)).toMatchObject({ payload: { text: '第一段第二段' }, type: 'message.complete' })
  })

  it('resolves slash completions from backend JSON-RPC responses', async () => {
    const gw = new GatewayClient()
    gw.start()
    await vi.waitFor(() => expect(FakeWebSocket.instances).toHaveLength(1))

    const socket = FakeWebSocket.instances[0]!

    socket.open()

    const completion = gw.request('complete.slash', { text: '/st' })
    await vi.waitFor(() => expect(socket.sent.some(frame => frame.includes('"method":"complete.slash"'))).toBe(true))
    const request = JSON.parse(socket.sent.at(-1) ?? '{}') as { id: string; method: string }

    expect(request.method).toBe('complete.slash')
    socket.message(
      JSON.stringify({
        id: request.id,
        jsonrpc: '2.0',
        result: {
          items: [{ display: '/status', meta: '查看当前会话状态', text: '/status' }],
          replace_from: 0
        }
      })
    )
    await expect(completion).resolves.toEqual({
      items: [{ display: '/status', meta: '查看当前会话状态', text: '/status' }],
      replace_from: 0
    })
  })

  it('waits for websocket open and resolves RPC requests in attach mode', async () => {
    process.env.SOLONCLAW_TUI_GATEWAY_URL = 'ws://gateway.test/api/ws?token=abc'
    const gw = new GatewayClient()

    gw.start()
    const gatewaySocket = FakeWebSocket.instances[0]!
    const req = gw.request<{ ok: boolean }>('session.create', { cols: 80 })

    expect(gatewaySocket.sent).toHaveLength(0)
    gatewaySocket.open()
    await vi.waitFor(() => expect(findRpcFrame(gatewaySocket, 'session.create')).toBeTruthy())

    const frame = JSON.parse(findRpcFrame(gatewaySocket, 'session.create') ?? '{}') as { id: string; method: string }
    expect(frame.method).toBe('session.create')

    gatewaySocket.message(JSON.stringify({ id: frame.id, jsonrpc: '2.0', result: { ok: true } }))
    await expect(req).resolves.toEqual({ ok: true })

    gw.kill()
  })

  it('serializes RPC requests on one websocket so the Solon backend does not drop concurrent frames', async () => {
    process.env.SOLONCLAW_TUI_GATEWAY_URL = 'ws://gateway.test/api/ws?token=abc'
    const gw = new GatewayClient()

    gw.start()
    const gatewaySocket = FakeWebSocket.instances[0]!
    gatewaySocket.open()

    const first = gw.request<{ ok: boolean }>('config.get', { key: 'full' })
    const second = gw.request<{ ok: boolean }>('setup.status', {})

    await vi.waitFor(() => expect(findRpcFrame(gatewaySocket, 'config.get')).toBeTruthy())
    expect(findRpcFrame(gatewaySocket, 'setup.status')).toBeFalsy()

    const firstFrame = JSON.parse(findRpcFrame(gatewaySocket, 'config.get') ?? '{}') as { id: string }
    gatewaySocket.message(JSON.stringify({ id: firstFrame.id, jsonrpc: '2.0', result: { ok: true } }))

    await vi.waitFor(() => expect(findRpcFrame(gatewaySocket, 'setup.status')).toBeTruthy())
    const secondFrame = JSON.parse(findRpcFrame(gatewaySocket, 'setup.status') ?? '{}') as { id: string }
    gatewaySocket.message(JSON.stringify({ id: secondFrame.id, jsonrpc: '2.0', result: { ok: true } }))

    await expect(first).resolves.toEqual({ ok: true })
    await expect(second).resolves.toEqual({ ok: true })

    gw.kill()
  })

  it('mirrors event frames to sidecar websocket when configured', async () => {
    process.env.SOLONCLAW_TUI_GATEWAY_URL = 'ws://gateway.test/api/ws?token=abc'
    process.env.SOLONCLAW_TUI_SIDECAR_URL = 'ws://gateway.test/api/pub?token=abc&channel=demo'

    const gw = new GatewayClient()
    const seen: string[] = []

    gw.on('event', ev => seen.push(ev.type))
    gw.start()

    const gatewaySocket = FakeWebSocket.instances[0]!
    gatewaySocket.open()
    await vi.waitFor(() => expect(FakeWebSocket.instances).toHaveLength(2))

    const sidecarSocket = FakeWebSocket.instances[1]!

    sidecarSocket.open()
    gw.drain()

    const eventFrame = JSON.stringify({
      jsonrpc: '2.0',
      method: 'event',
      params: { type: 'tool.start', payload: { tool_id: 't1' } }
    })

    gatewaySocket.message(eventFrame)

    expect(seen).toContain('tool.start')
    expect(sidecarSocket.sent).toContain(eventFrame)

    gw.kill()
  })

  it('emits exit when attached websocket closes', () => {
    process.env.SOLONCLAW_TUI_GATEWAY_URL = 'ws://gateway.test/api/ws?token=abc'
    const gw = new GatewayClient()
    const exits: Array<null | number> = []

    gw.on('exit', code => exits.push(code))
    gw.start()

    const gatewaySocket = FakeWebSocket.instances[0]!

    gatewaySocket.open()
    gw.drain()
    gatewaySocket.close(1011)

    expect(exits).toEqual([1011])
    expect(gw.getLogTail(20)).toContain('[lifecycle] websocket close code=1011')
    expect(gw.getLogTail(20)).toContain('[lifecycle] transport exit code=1011')
  })

  it('rejects pending RPCs with websocket wording when the attached socket closes', async () => {
    process.env.SOLONCLAW_TUI_GATEWAY_URL = 'ws://gateway.test/api/ws?token=abc'
    const gw = new GatewayClient()

    gw.start()
    const gatewaySocket = FakeWebSocket.instances[0]!

    gatewaySocket.open()
    gw.drain()

    const req = gw.request('session.create', {})
    await vi.waitFor(() => expect(findRpcFrame(gatewaySocket, 'session.create')).toBeTruthy())

    gatewaySocket.close(1011)

    await expect(req).rejects.toThrow(/gateway websocket closed \(1011\)/)
  })

  it('rejects pending RPCs when kill() closes the attached websocket', async () => {
    process.env.SOLONCLAW_TUI_GATEWAY_URL = 'ws://gateway.test/api/ws?token=abc'
    const gw = new GatewayClient()

    gw.start()
    const gatewaySocket = FakeWebSocket.instances[0]!

    gatewaySocket.open()
    gw.drain()

    const req = gw.request('session.create', {})
    await vi.waitFor(() => expect(findRpcFrame(gatewaySocket, 'session.create')).toBeTruthy())

    gw.kill('test.shutdown')

    await expect(req).rejects.toThrow(/gateway closed/)
    expect(gw.getLogTail(20)).toContain('[lifecycle] GatewayClient.kill reason=test.shutdown')
  })

  it('reattaches when SOLONCLAW_TUI_GATEWAY_URL rotates between requests', async () => {
    process.env.SOLONCLAW_TUI_GATEWAY_URL = 'ws://gateway-old.test/api/ws?token=abc'
    const gw = new GatewayClient()

    gw.start()
    const firstSocket = FakeWebSocket.instances[0]!

    firstSocket.open()
    gw.drain()

    const stale = gw.request('session.create', {})
    await vi.waitFor(() => expect(findRpcFrame(firstSocket, 'session.create')).toBeTruthy())

    process.env.SOLONCLAW_TUI_GATEWAY_URL = 'ws://gateway-new.test/api/ws?token=xyz'
    const next = gw.request('session.create', {})

    await expect(stale).rejects.toThrow(/gateway attach url changed/)
    await vi.waitFor(() => expect(FakeWebSocket.instances).toHaveLength(2))

    const secondSocket = FakeWebSocket.instances[1]!
    expect(secondSocket.url).toContain('gateway-new.test')

    secondSocket.open()
    await vi.waitFor(() => expect(findRpcFrame(secondSocket, 'session.create')).toBeTruthy())

    const frame = JSON.parse(findRpcFrame(secondSocket, 'session.create') ?? '{}') as { id: string }
    secondSocket.message(JSON.stringify({ id: frame.id, jsonrpc: '2.0', result: { ok: true } }))

    await expect(next).resolves.toEqual({ ok: true })
    gw.kill()
  })

  it('redacts query string secrets in attach failure logs and events', () => {
    process.env.SOLONCLAW_TUI_GATEWAY_URL = 'ws://gateway.test/api/ws?token=hunter2&channel=secret'
    delete (globalThis as { WebSocket?: unknown }).WebSocket

    const gw = new GatewayClient()
    const stderrLines: string[] = []

    gw.on('event', ev => {
      if (ev.type === 'gateway.stderr' && typeof ev.payload?.line === 'string') {
        stderrLines.push(ev.payload.line)
      }
    })
    gw.start()
    gw.drain()

    expect(stderrLines.length).toBeGreaterThan(0)

    for (const line of stderrLines) {
      expect(line).not.toContain('hunter2')
      expect(line).not.toContain('channel=secret')
    }

    expect(gw.getLogTail(20)).not.toContain('hunter2')
    expect(gw.getLogTail(20)).not.toContain('channel=secret')

    gw.kill()
  })

  it('redacts attach URL secrets when the WebSocket constructor throws', () => {
    const secretUrl = 'ws://gateway.test/api/ws?token=hunter2&channel=secret'

    process.env.SOLONCLAW_TUI_GATEWAY_URL = secretUrl
    ;(globalThis as { WebSocket?: unknown }).WebSocket = class ThrowingWebSocket extends FakeWebSocket {
      constructor(url: string) {
        throw new TypeError(`Invalid URL: ${url}`)
      }
    } as unknown as typeof WebSocket

    const gw = new GatewayClient()

    gw.start()
    gw.drain()

    const tail = gw.getLogTail(20)
    expect(tail).not.toContain('hunter2')
    expect(tail).not.toContain('channel=secret')
    expect(tail).not.toContain(secretUrl)
    expect(tail).toContain('ws://gateway.test/api/ws?***')

    gw.kill()
  })

  it('redacts sidecar URL secrets when the WebSocket constructor throws', async () => {
    const sidecarUrl = 'ws://gateway.test/api/pub?token=hunter2&channel=secret'

    process.env.SOLONCLAW_TUI_GATEWAY_URL = 'ws://gateway.test/api/ws?token=abc'
    process.env.SOLONCLAW_TUI_SIDECAR_URL = sidecarUrl
    ;(globalThis as { WebSocket?: unknown }).WebSocket = class ThrowingSidecarWebSocket extends FakeWebSocket {
      constructor(url: string) {
        if (url.includes('/api/pub')) {
          throw new TypeError(`Invalid URL: ${url}`)
        }

        super(url)
      }
    } as unknown as typeof WebSocket

    const gw = new GatewayClient()

    gw.start()
    const gatewaySocket = FakeWebSocket.instances[0]!
    gatewaySocket.open()
    await vi.waitFor(() => expect(gw.getLogTail(20)).toContain('[sidecar] failed to connect'))

    const tail = gw.getLogTail(20)
    expect(tail).not.toContain('hunter2')
    expect(tail).not.toContain('channel=secret')
    expect(tail).not.toContain(sidecarUrl)
    expect(tail).toContain('ws://gateway.test/api/pub?***')

    gw.kill()
  })

  it('redacts user-info credentials even on URLs the WHATWG parser rejects', () => {
    const fixture = 'ws://alice:hunter2@gateway.test:99999/api/ws?token=secret'
    expect(() => new URL(fixture)).toThrow()

    process.env.SOLONCLAW_TUI_GATEWAY_URL = fixture
    delete (globalThis as { WebSocket?: unknown }).WebSocket

    const gw = new GatewayClient()
    const stderrLines: string[] = []

    gw.on('event', ev => {
      if (ev.type === 'gateway.stderr' && typeof ev.payload?.line === 'string') {
        stderrLines.push(ev.payload.line)
      }
    })
    gw.start()
    gw.drain()

    expect(stderrLines.length).toBeGreaterThan(0)

    for (const line of stderrLines) {
      expect(line).not.toContain('alice')
      expect(line).not.toContain('hunter2')
      expect(line).not.toContain('token=secret')
    }

    const tail = gw.getLogTail(20)
    expect(tail).not.toContain('alice')
    expect(tail).not.toContain('hunter2')
    expect(tail).not.toContain('token=secret')

    gw.kill()
  })
})
