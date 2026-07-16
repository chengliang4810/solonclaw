import assert from 'node:assert/strict'

const values = new Map<string, string>()

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
  value: { location: { origin: 'https://local.example' } },
  configurable: true,
})

const { chatCacheKey, clearChatCacheStorage, recoverChatCacheQuota } = await import('../src/shared/chatCacheScope.ts')

const accountAKey = chatCacheKey('', 'account-a-scope', 'sessions')
assert.ok(accountAKey)
localStorage.setItem(accountAKey, 'account-a-data')
assert.equal(
  chatCacheKey('', 'account-a-scope', 'sessions'),
  accountAKey,
  'same server and auth subject should reuse one cache namespace',
)

const accountBKey = chatCacheKey('', 'account-b-scope', 'sessions')
assert.ok(accountBKey)
assert.notEqual(accountBKey, accountAKey, 'different auth subjects must not share cache keys')
assert.equal(localStorage.getItem(accountAKey), null, 'switching auth subject should remove stale chat data')
localStorage.setItem(accountBKey, 'account-b-data')

const remoteKey = chatCacheKey('https://remote.example/dashboard', 'account-b-scope', 'sessions')
assert.ok(remoteKey)
assert.notEqual(remoteKey, accountBKey, 'different API servers must not share cache keys')
assert.equal(localStorage.getItem(accountBKey), null, 'switching API server should remove stale chat data')

localStorage.setItem(remoteKey, 'current-account-data')
const ownerBeforeRecovery = localStorage.getItem('solonclaw_chat_cache_owner_v3')
recoverChatCacheQuota()
assert.equal(localStorage.getItem(remoteKey), null, 'quota recovery should remove current cache data')
assert.equal(localStorage.getItem('solonclaw_chat_cache_owner_v3'), ownerBeforeRecovery, 'quota recovery should preserve the current owner namespace')

localStorage.setItem('solonclaw_sessions_cache_v2', 'legacy-data')
localStorage.setItem('solonclaw_theme', 'dark')
clearChatCacheStorage()

assert.equal(localStorage.getItem(remoteKey), null, 'explicit invalidation should remove scoped chat data')
assert.equal(localStorage.getItem('solonclaw_sessions_cache_v2'), null, 'explicit invalidation should remove legacy chat data')
assert.equal(localStorage.getItem('solonclaw_theme'), 'dark', 'chat invalidation must preserve unrelated preferences')
assert.equal(
  [...values.values()].some(value => value.includes('account-a-token') || value.includes('account-b-token')),
  false,
  'chat cache metadata must not persist API tokens',
)
