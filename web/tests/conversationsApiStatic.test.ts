import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/conversations.ts', import.meta.url), 'utf8')

assert.ok(api.includes('/api/sessions?'), 'live conversation summaries should reuse dashboard sessions')
assert.ok(api.includes('/api/sessions/${encodeURIComponent(sessionId)}/messages'), 'live conversation detail should reuse session messages')
assert.ok(!api.includes('return []'), 'live conversation summaries must not always return an empty list')
