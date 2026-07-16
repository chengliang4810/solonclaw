import assert from 'node:assert/strict'

const store = new Map<string, string>()
const windowListeners = new Map<string, Set<(event: { key: string | null }) => void>>()
let failingSetKey = ''
let failingRemoveKey = ''

Object.defineProperty(globalThis, 'localStorage', {
  value: {
    get length() { return store.size },
    getItem: (key: string) => store.get(key) || null,
    key: (index: number) => [...store.keys()][index] || null,
    setItem: (key: string, value: string) => {
      if (key === failingSetKey) throw new Error(`set failed: ${key}`)
      store.set(key, value)
    },
    removeItem: (key: string) => {
      if (key === failingRemoveKey) throw new Error(`remove failed: ${key}`)
      store.delete(key)
    },
  },
  configurable: true,
})

Object.defineProperty(globalThis, 'window', {
  value: {
    __LOGIN_TOKEN__: 'url-token',
    addEventListener: (type: string, listener: (event: { key: string | null }) => void) => {
      const listeners = windowListeners.get(type) || new Set()
      listeners.add(listener)
      windowListeners.set(type, listeners)
    },
    location: {
      hostname: '127.0.0.1',
      origin: 'http://127.0.0.1:5173',
    },
  },
  configurable: true,
})

const { clearApiKey, getApiKey, getAuthScopeId, getBaseUrlValue, onAuthContextChange, setApiKey, setServerUrl } = await import('../src/api/sessionAuth.ts')
const { chatCacheKey } = await import('../src/shared/chatCacheScope.ts')

let authChanges = 0
onAuthContextChange(() => { authChanges += 1 })
localStorage.setItem('solonclaw_sessions_cache_v2', 'old-account-data')

assert.equal(getApiKey(), 'url-token', 'URL token should be usable before an auth failure')

clearApiKey()

assert.equal(getApiKey(), '', 'auth failure should clear both stored and injected tokens')
assert.equal(window.__LOGIN_TOKEN__, '', 'injected URL token should not keep the route authenticated')
assert.equal(localStorage.getItem('solonclaw_sessions_cache_v2'), null, 'logout should clear old chat cache data')
assert.equal(authChanges, 1, 'logout should notify in-memory auth-scoped stores')

setApiKey('stored-token')

assert.equal(getApiKey(), 'stored-token', 'stored token should still work after clearing injected token')
assert.equal(authChanges, 2, 'switching auth subject should notify in-memory stores')
assert.ok(chatCacheKey('', getAuthScopeId(), 'sessions'), 'authenticated subjects should receive a cache namespace')
assert.equal(
  localStorage.getItem('solonclaw_chat_cache_owner_v3')?.includes('stored-token'),
  false,
  'chat cache owner metadata must not persist the API token',
)

setServerUrl('http://127.0.0.1:8080')

assert.equal(getBaseUrlValue(), '', 'stale localhost server URL should not override the current dashboard origin')

setServerUrl('https://dashboard.example.com')

assert.equal(getBaseUrlValue(), 'https://dashboard.example.com', 'non-local server URL should stay configurable')
assert.equal(authChanges, 3, 'switching the effective API server should notify in-memory stores')

localStorage.setItem('solonclaw_sessions_cache_v2', 'orphaned-data')
clearApiKey()

assert.equal(localStorage.getItem('solonclaw_sessions_cache_v2'), null, 'logout should clear orphaned cache even without a current token')
assert.equal(authChanges, 4, 'logout should always reset auth-scoped stores')

localStorage.setItem('solonclaw_sessions_cache_v2', 'other-tab-old-data')
for (const listener of windowListeners.get('storage') || []) listener({ key: 'solonclaw_api_key' })

assert.equal(localStorage.getItem('solonclaw_sessions_cache_v2'), null, 'another tab auth switch should clear this tab cache')
assert.equal(authChanges, 5, 'another tab auth switch should reset this tab auth-scoped stores')

for (const listener of windowListeners.get('storage') || []) listener({ key: null })
assert.equal(authChanges, 6, 'another tab localStorage.clear should reset this tab auth-scoped stores')

localStorage.setItem('solonclaw_sessions_cache_v2', 'scope-write-failure-data')
failingSetKey = 'solonclaw_auth_scope_v1'
assert.doesNotThrow(() => setApiKey('quota-token'), 'auth scope storage failure must not fail a successful token update')
assert.equal(getApiKey(), 'quota-token', 'token update should remain effective when cache scope storage fails')
assert.equal(localStorage.getItem('solonclaw_sessions_cache_v2'), null, 'scope write failure must still clear old account cache')
assert.equal(getAuthScopeId(), '', 'scope write failure should disable chat caching')
assert.equal(authChanges, 7, 'scope write failure must still reset in-memory auth state')
failingSetKey = ''

window.__LOGIN_TOKEN__ = 'injected-token'
failingRemoveKey = 'solonclaw_api_key'
assert.throws(() => clearApiKey(), /remove failed/, 'token storage removal failure should remain observable')
assert.equal(window.__LOGIN_TOKEN__, '', 'stored-token removal failure must still clear the injected token')
assert.equal(authChanges, 8, 'failed logout must still reset in-memory auth state')
failingRemoveKey = ''
clearApiKey()
