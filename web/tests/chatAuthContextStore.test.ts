import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createPinia, setActivePinia } from 'pinia'
import { createServer, type Plugin } from 'vite'

interface SessionSummary {
  id: string
  source: string
  model: string
  title: string
  started_at: number
  ended_at: null
  message_count: number
  tool_call_count: number
  input_tokens: number
  output_tokens: number
  cache_read_tokens: number
  cache_write_tokens: number
  reasoning_tokens: number
  provider: null
}

interface ChatSessionsMock {
  fetchSessions: () => Promise<SessionSummary[]>
}

interface ChatRunMock {
  cancelRun: () => Promise<void>
  startRun: (body: { session_id?: string }) => Promise<{ run_id: string, status: string, session_id: string }>
  streamRunEvents: (
    runId: string,
    onEvent: (event: { event: string, delta?: string }) => void,
    onDone: () => void,
    onError: (error: Error) => void,
  ) => AbortController
}

declare global {
  var __CHAT_SESSIONS_MOCK__: ChatSessionsMock
  var __CHAT_RUN_MOCK__: ChatRunMock
}

const values = new Map<string, string>()
const windowListeners = new Map<string, Set<(event: { key: string | null }) => void>>()
const documentListeners = new Map<string, Set<() => void>>()
const streamControllers: AbortController[] = []
const streamEventCallbacks: Array<(event: { event: string, delta?: string }) => void> = []
Object.defineProperty(globalThis, 'localStorage', {
  value: {
    get length() { return values.size },
    getItem: (key: string) => values.get(key) ?? null,
    key: (index: number) => [...values.keys()][index] ?? null,
    removeItem: (key: string) => values.delete(key),
    setItem: (key: string, value: string) => values.set(key, value),
  },
  configurable: true,
})
Object.defineProperty(globalThis, 'window', {
  value: {
    __LOGIN_TOKEN__: '',
    addEventListener: (type: string, listener: (event: { key: string | null }) => void) => {
      const listeners = windowListeners.get(type) || new Set()
      listeners.add(listener)
      windowListeners.set(type, listeners)
    },
    location: { hostname: 'dashboard.example', origin: 'https://dashboard.example' },
  },
  configurable: true,
})
Object.defineProperty(globalThis, 'document', {
  value: {
    visibilityState: 'hidden',
    addEventListener: (type: string, listener: () => void) => {
      const listeners = documentListeners.get(type) || new Set()
      listeners.add(listener)
      documentListeners.set(type, listeners)
    },
    removeEventListener: (type: string, listener: () => void) => documentListeners.get(type)?.delete(listener),
  },
  configurable: true,
})

const session = (id: string, title = id): SessionSummary => ({
  id,
  source: 'api_server',
  model: 'test-model',
  title,
  started_at: 1,
  ended_at: null,
  message_count: 1,
  tool_call_count: 0,
  input_tokens: 0,
  output_tokens: 0,
  cache_read_tokens: 0,
  cache_write_tokens: 0,
  reasoning_tokens: 0,
  provider: null,
})

/** 用最小虚拟 API 隔离 Store，直接验证认证切换时的状态行为。 */
function chatApiMocks(): Plugin {
  const modules: Record<string, string> = {
    '@/api/client': `
      export { getApiKey, getBaseUrlValue } from '/src/api/sessionAuth.ts'
      export function getManagementProfile() { return '' }
    `,
    '@/api/solonclaw/chat': `
      export function cancelRun() { return globalThis.__CHAT_RUN_MOCK__.cancelRun() }
      export function startRun(body) { return globalThis.__CHAT_RUN_MOCK__.startRun(body) }
      export function streamRunEvents(...args) { return globalThis.__CHAT_RUN_MOCK__.streamRunEvents(...args) }
      export async function uploadChatFiles() { return [] }
    `,
    '@/api/solonclaw/runs': `export async function fetchRunDetail() { return { run: { status: 'success' } } }`,
    '@/api/solonclaw/sessions': `
      export function fetchSessions() { return globalThis.__CHAT_SESSIONS_MOCK__.fetchSessions() }
      export async function fetchLatestSessionDescendant(id) { return { session_id: id } }
      export async function fetchSession() { return null }
      export async function fetchSessionUsageSingle() { return null }
      export async function deleteSession() { return true }
    `,
    './app': `export function useAppStore() { return { selectedModel: '' } }`,
    './profiles': `export function useProfilesStore() { return { managedProfileName: 'default' } }`,
  }

  return {
    name: 'chat-auth-context-test-mocks',
    enforce: 'pre',
    resolveId(id, importer) {
      if ((id === './app' || id === './profiles') && !importer?.endsWith('/stores/solonclaw/chat.ts')) return null
      const sourceId = Object.keys(modules).find(candidate =>
        candidate === id
        || (candidate.startsWith('@/') && (
          id.endsWith(`/src/${candidate.slice(2)}`)
          || id.endsWith(`/src/${candidate.slice(2)}.ts`)
        )),
      )
      return sourceId ? `\0chat-test:${sourceId}` : null
    },
    load(id) {
      const sourceId = id.startsWith('\0chat-test:') ? id.slice('\0chat-test:'.length) : ''
      return modules[sourceId] ?? null
    },
  }
}

const webRoot = resolve(fileURLToPath(new URL('..', import.meta.url)))
const server = await createServer({
  root: webRoot,
  configFile: false,
  appType: 'custom',
  server: { middlewareMode: true },
  resolve: { alias: { '@': resolve(webRoot, 'src') } },
  plugins: [chatApiMocks()],
})

try {
  const { setApiKey } = await server.ssrLoadModule('/src/api/sessionAuth.ts') as typeof import('../src/api/sessionAuth.ts')
  const { useChatStore } = await server.ssrLoadModule('/src/stores/solonclaw/chat.ts') as typeof import('../src/stores/solonclaw/chat.ts')

  setApiKey('account-a')
  globalThis.__CHAT_SESSIONS_MOCK__ = { fetchSessions: async () => [session('account-a-session')] }
  globalThis.__CHAT_RUN_MOCK__ = {
    cancelRun: async () => {},
    startRun: async body => ({ run_id: 'run', status: 'running', session_id: body.session_id || 'session' }),
    streamRunEvents: (_runId, onEvent) => {
      const controller = new AbortController()
      streamControllers.push(controller)
      streamEventCallbacks.push(onEvent)
      return controller
    },
  }
  setActivePinia(createPinia())
  const store = useChatStore()
  await store.loadSessions()
  assert.deepEqual(store.sessions.map(item => item.id), ['account-a-session'], 'store should load the current account sessions')

  for (const listener of windowListeners.get('storage') || []) listener({ key: 'solonclaw_api_key' })
  assert.equal(store.sessions.length, 0, 'another tab auth switch should clear this tab in-memory sessions')
  await store.loadSessions()
  for (const listener of windowListeners.get('storage') || []) listener({ key: null })
  assert.equal(store.sessions.length, 0, 'another tab localStorage.clear should clear this tab in-memory sessions')
  await store.loadSessions()

  setApiKey('account-b')
  assert.equal(store.sessions.length, 0, 'auth switch should clear in-memory sessions immediately')
  assert.equal(store.activeSession, null, 'auth switch should clear the active session immediately')
  assert.equal(store.sessionsLoaded, false, 'auth switch should require a fresh session load')

  let resolveLate!: (sessions: SessionSummary[]) => void
  globalThis.__CHAT_SESSIONS_MOCK__.fetchSessions = () => new Promise(resolve => { resolveLate = resolve })
  const lateLoad = store.loadSessions()
  setApiKey('account-c')
  resolveLate([session('account-b-late-session')])
  await lateLoad
  assert.equal(store.sessions.length, 0, 'late responses from the previous auth context must be discarded')
  assert.equal(store.sessionsLoaded, false, 'late responses must not mark the new auth context as loaded')

  globalThis.__CHAT_SESSIONS_MOCK__.fetchSessions = async () => [session('shared-session', 'account-c-title')]
  await store.loadSessions()

  let resolveStart!: (run: { run_id: string, status: string, session_id: string }) => void
  globalThis.__CHAT_RUN_MOCK__.startRun = () => new Promise(resolve => { resolveStart = resolve })
  const lateSend = store.sendMessage('account-c-message')
  setApiKey('account-d')
  globalThis.__CHAT_SESSIONS_MOCK__.fetchSessions = async () => [session('shared-session', 'account-d-title')]
  await store.loadSessions()
  resolveStart({ run_id: 'old-run', status: 'running', session_id: 'shared-session' })
  await lateSend
  assert.equal(store.activeSession?.title, 'account-d-title', 'late send rollback must not overwrite a new account session with the same id')

  globalThis.__CHAT_RUN_MOCK__.startRun = async body => ({ run_id: 'run', status: 'running', session_id: body.session_id || 'session' })
  await store.sendMessage('account-d-message')
  assert.equal(store.isStreaming, true, 'old account should have an active stream before cancellation')

  let resolveCancel!: () => void
  globalThis.__CHAT_RUN_MOCK__.cancelRun = () => new Promise(resolve => { resolveCancel = resolve })
  const lateStop = store.stopStreaming()
  setApiKey('account-e')
  globalThis.__CHAT_SESSIONS_MOCK__.fetchSessions = async () => [session('shared-session')]
  await store.loadSessions()
  await store.sendMessage('account-e-message')
  assert.equal(store.isStreaming, true, 'new account should start its own stream with the same session id')
  resolveCancel()
  await lateStop
  assert.equal(store.isStreaming, true, 'late cancellation from the old account must not abort the new account stream')

  globalThis.__CHAT_SESSIONS_MOCK__.fetchSessions = async () => [session('disposed-store-session')]
  await store.loadSessions()
  const disposedController = streamControllers.at(-1)
  const disposedEventCallback = streamEventCallbacks.at(-1)
  assert.ok(disposedController && disposedEventCallback, 'dispose test requires an active stream callback')
  store.$dispose()
  assert.equal(disposedController.signal.aborted, true, 'disposing the store should abort active SSE streams')
  assert.equal(store.sessions.length, 0, 'disposing the store should clear auth-scoped in-memory data')
  assert.equal(documentListeners.get('visibilitychange')?.size || 0, 0, 'disposing the store should remove visibility listeners')
  setApiKey('account-f')
  disposedEventCallback({ event: 'message.delta', delta: 'late-disposed-account-data' })
  assert.equal(
    [...values.values()].some(value => value.includes('late-disposed-account-data')),
    false,
    'late SSE callbacks from a disposed store must not write into a new auth scope',
  )
} finally {
  await server.close()
  delete globalThis.__CHAT_SESSIONS_MOCK__
  delete globalThis.__CHAT_RUN_MOCK__
}
