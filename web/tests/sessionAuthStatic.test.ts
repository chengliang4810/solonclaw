import assert from 'node:assert/strict'

const store = new Map<string, string>()
;(globalThis as any).window = {
  __LOGIN_TOKEN__: 'url-token',
}
;(globalThis as any).localStorage = {
  getItem(key: string) {
    return store.get(key) || null
  },
  setItem(key: string, value: string) {
    store.set(key, value)
  },
  removeItem(key: string) {
    store.delete(key)
  },
}

const auth = await import('../src/api/sessionAuth.ts')

auth.setApiKey('stored-token')
assert.equal(auth.getApiKey(), 'stored-token')

auth.clearApiKey()
assert.equal(auth.getApiKey(), '')
assert.equal(auth.hasApiKey(), false)
