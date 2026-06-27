import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/sessions.ts', import.meta.url), 'utf8')

assert.ok(api.includes('/api/search?'), 'session search should call the backend search endpoint')
assert.ok(!api.includes('/api/sessions/search'), 'session search should not call a missing sessions search endpoint')
assert.ok(api.includes('function sessionPath(id: string)'), 'session path parameters should use one encoding helper')
assert.ok(api.includes('function checkpointPath(id: string)'), 'checkpoint path parameters should use one encoding helper')
assert.ok(!api.includes('/api/sessions/${id}'), 'sessions API should not interpolate raw session id path segments')
assert.ok(!api.includes('/api/checkpoints/${id}'), 'sessions API should not interpolate raw checkpoint id path segments')
assert.ok(api.includes('match_preview'), 'session search should map the backend match preview field')
assert.ok(api.includes('updated_at'), 'session search should map the backend updated timestamp')
