import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const chatApi = readFileSync(new URL('../src/api/solonclaw/chat.ts', import.meta.url), 'utf8')
const handlerUses = chatApi.match(/handleDashboardAuthFailure/g) || []

assert.ok(
  chatApi.includes("import { getApiKey, getBaseUrlValue, handleDashboardAuthFailure, request } from '../client'"),
  'chat API should import the shared dashboard auth failure handler',
)
assert.ok(
  handlerUses.length >= 3,
  'upload and SSE direct fetch paths should use the shared dashboard auth failure handler',
)
