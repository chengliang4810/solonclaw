import assert from 'node:assert/strict'

const OPEN = 1
const CLOSED = 3

type Handler = ((event?: unknown) => void) | null

class FakeWebSocket {
  static readonly OPEN = OPEN
  static readonly CLOSED = CLOSED
  static instances: FakeWebSocket[] = []

  readyState = OPEN
  sent: string[] = []
  onopen: Handler = null
  onmessage: Handler = null
  onerror: Handler = null
  onclose: Handler = null
  readonly url: string

  constructor(url: string) {
    this.url = url
    FakeWebSocket.instances.push(this)
  }

  send(message: string) {
    this.sent.push(message)
  }

  close() {
    this.readyState = CLOSED
  }
}

const originalWebSocket = globalThis.WebSocket
const originalLocation = globalThis.location
const originalNavigator = globalThis.navigator
const originalWindow = globalThis.window
const originalLocalStorage = globalThis.localStorage

Object.defineProperty(globalThis, 'WebSocket', { configurable: true, value: FakeWebSocket })
Object.defineProperty(globalThis, 'location', { configurable: true, value: { protocol: 'http:', host: 'localhost:5173' } })
Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { userAgent: 'test-agent' } })
Object.defineProperty(globalThis, 'window', { configurable: true, value: { setTimeout, clearTimeout } })
Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  value: {
    getItem: () => null,
    removeItem: () => {},
    setItem: () => {},
  },
})

try {
  const { TuiJsonRpcClient } = await import('../src/tui/tuiClient.ts')
  const events: any[] = []
  const client = new TuiJsonRpcClient((event) => events.push(event))

  client.connect()
  const first = FakeWebSocket.instances[0]
  first.onopen?.()

  client.connect()
  const second = FakeWebSocket.instances[1]
  second.onopen?.()

  const currentRequest = client.request('session.controls', { sessionId: 's1' })
  let currentRejected = false
  currentRequest.catch(() => {
    currentRejected = true
  })

  first.onerror?.()
  first.onclose?.()
  await Promise.resolve()

  assert.equal(client.isConnected(), true)
  assert.equal(currentRejected, false)
  assert.deepEqual(
    events.map((event) => event.type === 'connection' ? event.payload.state : null).filter(Boolean),
    ['connecting', 'connected', 'connecting', 'connected'],
  )

  second.onmessage?.({
    data: JSON.stringify({
      type: 'rpc.result',
      id: '1',
      sessionId: 's1',
      payload: { controls: ['retry'] },
    }),
  })

  const result = await currentRequest
  assert.deepEqual(result, { controls: ['retry'] })
} finally {
  Object.defineProperty(globalThis, 'WebSocket', { configurable: true, value: originalWebSocket })
  Object.defineProperty(globalThis, 'location', { configurable: true, value: originalLocation })
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: originalNavigator })
  Object.defineProperty(globalThis, 'window', { configurable: true, value: originalWindow })
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: originalLocalStorage })
}
