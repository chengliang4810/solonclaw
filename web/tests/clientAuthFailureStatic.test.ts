import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const clientApi = readFileSync(new URL('../src/api/client.ts', import.meta.url), 'utf8')
const chatApi = readFileSync(new URL('../src/api/solonclaw/chat.ts', import.meta.url), 'utf8')

assert.ok(
  clientApi.includes('if (handleDashboardAuthFailure(res.status, text))'),
  'shared request client should branch on dashboard auth failures',
)
assert.ok(
  clientApi.includes("throw new Error('Unauthorized')"),
  'shared request client should not expose raw dashboard auth failure bodies to views',
)

const chatFailureBranches = chatApi.match(/handleDashboardAuthFailure/g) || []
assert.ok(
  chatFailureBranches.length >= 3,
  'direct upload and SSE fetch paths should suppress raw dashboard auth failure bodies',
)
