import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const runsApi = readFileSync(new URL('../src/api/solonclaw/runs.ts', import.meta.url), 'utf8')
const runsView = readFileSync(new URL('../src/views/solonclaw/RunsView.vue', import.meta.url), 'utf8')

assert.ok(runsApi.includes('/api/runs/${runId}/control'), 'run control endpoint should be wrapped')
assert.ok(runsView.includes('controlRun'), 'Runs view should import run control wrapper')
assert.ok(runsView.includes("handleRunControl('resume')"), 'Runs view should expose recoverable run resume')
assert.ok(runsView.includes("handleRunControl('background')"), 'Runs view should expose running run backgrounding')
assert.ok(runsView.includes("handleRunControl('interrupt')"), 'Runs view should expose running run interrupt')
assert.ok(runsView.includes('selectedRunRecoverable'), 'Runs view should only show resume for recoverable runs')
