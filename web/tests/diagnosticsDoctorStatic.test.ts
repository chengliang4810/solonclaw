import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const diagnosticsApi = readFileSync(new URL('../src/api/solonclaw/diagnostics.ts', import.meta.url), 'utf8')
const diagnosticsView = readFileSync(new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url), 'utf8')

assert.ok(diagnosticsApi.includes('/api/diagnostics/doctor'), 'doctor diagnostics endpoint should be wrapped')
assert.ok(diagnosticsApi.includes('fetchDiagnosticsDoctor'), 'doctor diagnostics wrapper should be exported')
assert.ok(diagnosticsView.includes('fetchDiagnosticsDoctor'), 'Diagnostics view should load doctor diagnostics')
assert.ok(diagnosticsView.includes('doctorDiagnostics = ref'), 'Diagnostics view should keep doctor diagnostics')
assert.ok(diagnosticsView.includes("t('diagnostics.doctor')"), 'Diagnostics view should render doctor section')
