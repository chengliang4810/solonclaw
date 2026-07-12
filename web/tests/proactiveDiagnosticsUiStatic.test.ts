import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/diagnostics.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes('proactive?:'), 'diagnostics API should type backend proactive payload')
assert.ok(view.includes('proactiveDiagnostics'), 'diagnostics view should normalize proactive diagnostics')
assert.ok(view.includes("t('diagnostics.proactiveDiagnostics')"), 'diagnostics view should render proactive diagnostics panel')
assert.ok(view.includes("t('diagnostics.proactiveLastSkipReason')"), 'diagnostics view should show proactive skip reason')
assert.ok(zh.includes("proactiveDiagnostics: '主动协作诊断'"), 'Chinese locale should include proactive diagnostics label')
assert.ok(en.includes("proactiveDiagnostics: 'Proactive diagnostics'"), 'English locale should include proactive diagnostics label')
assert.ok(api.includes("'/api/diagnostics/proactive/retry'"), 'diagnostics API should expose explicit proactive retry')
assert.ok(view.includes('proactiveUnknownCandidates'), 'diagnostics view should list delivery-unknown candidates')
assert.ok(view.includes('window.confirm'), 'manual retry should require explicit confirmation')
assert.ok(view.includes('retryProactiveDelivery(candidate.candidate_id)'), 'retry action should target one selected candidate')
assert.ok(view.includes('v-if="proactiveUnknownCandidates.length"'), 'retry controls should only render for delivery-unknown candidates')
assert.ok(view.includes('v-if="!hasProactiveDiagnostics"'), 'diagnostics empty state should only render when diagnostics are absent')
assert.ok(view.includes('noProactiveUnknownDeliveries'), 'known diagnostics without unknown deliveries should show the correct empty state')
