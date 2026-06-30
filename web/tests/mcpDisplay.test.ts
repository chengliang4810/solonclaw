import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  MCP_TRANSPORT_OPTIONS,
  mcpStatusTone,
  mcpTimestampText,
  mcpTransportOptions,
} from '../src/shared/mcpDisplay.ts'

const view = readFileSync(new URL('../src/views/solonclaw/McpView.vue', import.meta.url), 'utf8')

assert.deepEqual(
  MCP_TRANSPORT_OPTIONS.map(option => option.value),
  ['stdio', 'streamable', 'streamable_stateless', 'sse'],
)
assert.deepEqual(
  MCP_TRANSPORT_OPTIONS.map(option => option.label),
  ['stdio', 'streamable', 'streamable_stateless', 'sse'],
)
assert.notEqual(mcpTransportOptions(), MCP_TRANSPORT_OPTIONS)
assert.deepEqual(mcpTransportOptions(), MCP_TRANSPORT_OPTIONS)

assert.equal(mcpStatusTone('ready'), 'success')
assert.equal(mcpStatusTone('connected'), 'success')
assert.equal(mcpStatusTone('authenticated'), 'success')
assert.equal(mcpStatusTone('error'), 'error')
assert.equal(mcpStatusTone('blocked'), 'error')
assert.equal(mcpStatusTone('expired'), 'error')
assert.equal(mcpStatusTone('pending'), 'warning')
assert.equal(mcpStatusTone('configured'), 'warning')
assert.equal(mcpStatusTone('unknown'), 'default')
assert.equal(mcpStatusTone(undefined), 'default')

assert.equal(mcpTimestampText(undefined), '-')
assert.equal(mcpTimestampText(0), '-')
assert.match(mcpTimestampText(Date.UTC(2026, 0, 2, 3, 4, 5)), /2026/)

assert.ok(!view.includes('const transportOptions = ['), 'McpView should not inline transport options')
assert.ok(!view.includes('function statusType'), 'McpView should not inline MCP status colors')
assert.ok(!view.includes('function formatTime'), 'McpView should not inline MCP timestamp formatting')
assert.ok(view.includes("from '@/shared/mcpDisplay'"), 'McpView should reuse shared MCP display helpers')
