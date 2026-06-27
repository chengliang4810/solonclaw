import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const diagnosticsApi = readFileSync(new URL('../src/api/solonclaw/diagnostics.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(diagnosticsApi.includes('fetchApprovalStats'), 'diagnostics API should expose approval stats')
assert.ok(diagnosticsApi.includes("'/api/approval/stats'"), 'approval stats should call backend stats endpoint')
assert.ok(view.includes('approvalStats.value = await fetchApprovalStats()'), 'diagnostics view should load approval stats')
assert.ok(view.includes("t('diagnostics.approvalStats')"), 'diagnostics view should render approval stats')
assert.ok(zh.includes("approvalStats: '审批统计'"), 'Chinese locale should include approval stats label')
assert.ok(en.includes("approvalStats: 'Approval stats'"), 'English locale should include approval stats label')
