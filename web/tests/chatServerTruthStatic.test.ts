import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const store = readFileSync(new URL('../src/stores/solonclaw/chat.ts', import.meta.url), 'utf8')

assert.ok(
  store.includes('旧浏览器缓存不能覆盖服务端空列表'),
  'chat session loading should document that stale browser cache cannot override an empty server list',
)
assert.ok(
  store.includes('&& (readInFlight(s.id) || streamStates.value.has(s.id) || resumingRuns.value.has(s.id))'),
  'local-only sessions should survive only when they are still recoverable',
)
assert.ok(
  store.includes('activeSessionId.value = null') && store.includes('activeSession.value = null'),
  'chat store should clear the active session when neither server nor recoverable local sessions exist',
)
assert.ok(
  store.includes('removeItem(storageKey())'),
  'chat store should clear the persisted active session key when the server has no matching session',
)
