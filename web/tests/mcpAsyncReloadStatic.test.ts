import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/mcp.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/McpView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes('/api/mcp/reload/async'), 'MCP async reload endpoint should be wrapped')
assert.ok(api.includes('reloadAllMcpServersAsync'), 'MCP async reload wrapper should be exported')
assert.ok(view.includes('reloadAllServersAsync'), 'MCP view should expose async reload action')
assert.ok(view.includes("actionLoading === 'reload-all-async'"), 'MCP view should track async reload loading state separately')
assert.ok(zh.includes('后台重载'), 'Chinese locale should label async MCP reload')
assert.ok(en.includes('Background reload'), 'English locale should label async MCP reload')
