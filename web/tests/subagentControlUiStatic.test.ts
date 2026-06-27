import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const runsApi = readFileSync(new URL('../src/api/solonclaw/runs.ts', import.meta.url), 'utf8')
const runsView = readFileSync(new URL('../src/views/solonclaw/RunsView.vue', import.meta.url), 'utf8')

assert.ok(runsApi.includes('/api/runs/subagents/${subagentId}/control'), 'subagent control endpoint should be wrapped')
assert.ok(runsApi.includes('controlSubagent'), 'subagent control wrapper should be exported')
assert.ok(runsView.includes('controlSubagent'), 'Runs view should call subagent control')
assert.ok(runsView.includes('handleSubagentControl'), 'Runs view should expose a subagent control action')
assert.ok(runsView.includes("handleSubagentControl(subagent.subagent_id, 'interrupt')"), 'Runs view should offer interrupt per subagent')
assert.ok(runsView.includes("'pause_spawn'"), 'Runs view should offer spawn pause')
assert.ok(runsView.includes("'resume_spawn'"), 'Runs view should offer spawn resume')
