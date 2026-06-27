import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const systemApi = readFileSync(new URL('../src/api/solonclaw/system.ts', import.meta.url), 'utf8')
const diagnosticsView = readFileSync(new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url), 'utf8')

assert.ok(systemApi.includes('/health/detailed'), 'detailed health endpoint should be wrapped')
assert.ok(systemApi.includes('fetchDetailedHealth'), 'detailed health wrapper should be exported')
assert.ok(diagnosticsView.includes('fetchDetailedHealth'), 'Diagnostics view should load detailed health')
assert.ok(diagnosticsView.includes('detailedHealth = ref'), 'Diagnostics view should keep detailed health state')
assert.ok(diagnosticsView.includes("t('diagnostics.detailedHealth')"), 'Diagnostics view should render detailed health section')
assert.ok(diagnosticsView.includes('formatDuration'), 'Diagnostics view should format uptime')
