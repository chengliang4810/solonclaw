import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/diagnostics.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes('auditSecurity'), 'diagnostics API should expose security audit')
assert.ok(api.includes("'/api/diagnostics/security-audit'"), 'security audit should call backend endpoint')
assert.ok(view.includes("value: 'policy'"), 'diagnostics audit dropdown should expose policy audit')
assert.ok(view.includes("auditForm.action === 'policy'"), 'diagnostics view should explain policy audit mode')
assert.ok(view.includes("t('diagnostics.auditPolicyHint')"), 'diagnostics view should render policy audit hint')
assert.ok(zh.includes("policy: '完整策略'"), 'Chinese locale should include policy audit label')
assert.ok(en.includes("policy: 'Full policy'"), 'English locale should include policy audit label')
