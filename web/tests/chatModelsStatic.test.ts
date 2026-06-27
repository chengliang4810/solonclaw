import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const chatApi = readFileSync(new URL('../src/api/solonclaw/chat.ts', import.meta.url), 'utf8')

assert.ok(chatApi.includes('/api/models'), 'chat models should use backend models endpoint')
assert.ok(chatApi.includes('DashboardModelsResponse'), 'chat models should type the backend response')
assert.ok(!chatApi.includes('return { data: [] }'), 'chat models should not return a hard-coded empty list')
assert.ok(chatApi.includes('model.model || model.id'), 'chat models should map backend model identifiers')
