import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const store = readFileSync(new URL('../src/stores/solonclaw/chat.ts', import.meta.url), 'utf8')

assert.ok(
  store.includes('crypto.randomUUID'),
  'chat uid() should prefer crypto.randomUUID() to avoid client-side message id collisions',
)
assert.ok(
  store.includes('chatUidCounter'),
  'chat uid() fallback should include a module-level counter for same-millisecond ids',
)
