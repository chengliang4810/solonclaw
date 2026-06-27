import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const diagnosticsApi = readFileSync(new URL('../src/api/solonclaw/diagnostics.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(diagnosticsApi.includes('fetchDiagnosticsDoctor'), 'diagnostics API should expose doctor fetch')
assert.ok(diagnosticsApi.includes("'/api/diagnostics/doctor'"), 'doctor fetch should call backend doctor endpoint')
assert.ok(view.includes('doctor.value = await fetchDiagnosticsDoctor()'), 'diagnostics view should load doctor data')
assert.ok(view.includes("t('diagnostics.doctor')"), 'diagnostics view should render doctor panel')
assert.ok(zh.includes("doctor: 'Doctor 诊断'"), 'Chinese locale should include doctor label')
assert.ok(en.includes("doctor: 'Doctor diagnostics'"), 'English locale should include doctor label')
