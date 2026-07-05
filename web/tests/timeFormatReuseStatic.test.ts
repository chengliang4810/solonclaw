import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const files = [
  '../src/components/solonclaw/diagnostics/ApprovalEventsPanel.vue',
  '../src/components/solonclaw/files/FileList.vue',
  '../src/views/solonclaw/DiagnosticsView.vue',
  '../src/views/solonclaw/TuiRuntimeView.vue',
  '../src/shared/jobsDisplay.ts',
  '../src/shared/mcpDisplay.ts',
  '../src/shared/runsDisplay.ts',
]

for (const file of files) {
  const source = readFileSync(new URL(file, import.meta.url), 'utf8')
  assert.ok(source.includes('formatTimestampText'), `${file} should reuse shared timestamp formatter`)
  assert.ok(!source.includes('.toLocaleString()'), `${file} should not format timestamps directly`)
}
