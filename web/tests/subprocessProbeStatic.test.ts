import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const diagnosticsApi = readFileSync(new URL('../src/api/solonclaw/diagnostics.ts', import.meta.url), 'utf8')
const diagnosticsView = readFileSync(new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url), 'utf8')

assert.ok(
  diagnosticsApi.includes('/api/diagnostics/subprocess-environment/probe'),
  'subprocess environment probe endpoint should be wrapped',
)
assert.ok(diagnosticsApi.includes('probeSubprocessEnvironment'), 'probe wrapper should be exported')
assert.ok(diagnosticsView.includes('probeSubprocessEnvironment'), 'Diagnostics view should call the subprocess probe')
assert.ok(diagnosticsView.includes('runSubprocessProbe'), 'Diagnostics view should expose a probe action')
assert.ok(diagnosticsView.includes('subprocessProbeInput'), 'Diagnostics view should include an input for variable names')
