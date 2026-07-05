import assert from 'node:assert/strict'

const store = new Map<string, string>()

Object.defineProperty(globalThis, 'localStorage', {
  value: {
    getItem: (key: string) => store.get(key) || null,
    setItem: (key: string, value: string) => store.set(key, value),
    removeItem: (key: string) => store.delete(key),
  },
  configurable: true,
})

Object.defineProperty(globalThis, 'window', {
  value: {
    __LOGIN_TOKEN__: 'url-token',
    location: {
      hostname: '127.0.0.1',
      origin: 'http://127.0.0.1:5173',
    },
  },
  configurable: true,
})

const { clearApiKey, getApiKey, getBaseUrlValue, setApiKey, setServerUrl } = await import('../src/api/sessionAuth.ts')

assert.equal(getApiKey(), 'url-token', 'URL token should be usable before an auth failure')

clearApiKey()

assert.equal(getApiKey(), '', 'auth failure should clear both stored and injected tokens')
assert.equal(window.__LOGIN_TOKEN__, '', 'injected URL token should not keep the route authenticated')

setApiKey('stored-token')

assert.equal(getApiKey(), 'stored-token', 'stored token should still work after clearing injected token')

setServerUrl('http://127.0.0.1:8080')

assert.equal(getBaseUrlValue(), '', 'stale localhost server URL should not override the current dashboard origin')

setServerUrl('https://dashboard.example.com')

assert.equal(getBaseUrlValue(), 'https://dashboard.example.com', 'non-local server URL should stay configurable')
