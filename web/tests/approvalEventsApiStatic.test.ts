import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const diagnosticsApi = readFileSync(new URL('../src/api/solonclaw/diagnostics.ts', import.meta.url), 'utf8')
const diagnosticsView = readFileSync(new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url), 'utf8')

assert.ok(diagnosticsApi.includes('/api/approval/events'), 'approval events endpoint should be wrapped')
assert.ok(diagnosticsApi.includes('/api/approval/stats'), 'approval stats endpoint should be wrapped')
assert.ok(diagnosticsView.includes('fetchApprovalEvents'), 'Diagnostics view should load approval events')
assert.ok(diagnosticsView.includes('fetchApprovalStats'), 'Diagnostics view should load approval stats')
