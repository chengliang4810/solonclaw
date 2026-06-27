import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const runsApi = readFileSync(new URL('../src/api/solonclaw/runs.ts', import.meta.url), 'utf8')
const runsView = readFileSync(new URL('../src/views/solonclaw/RunsView.vue', import.meta.url), 'utf8')

assert.ok(runsApi.includes('/api/runs/subagents/active'), 'active subagents endpoint should be wrapped')
assert.ok(runsApi.includes('fetchActiveSubagents'), 'active subagents wrapper should be exported')
assert.ok(runsView.includes('fetchActiveSubagents'), 'Runs view should load active subagents')
assert.ok(runsView.includes('const activeSubagents = ref<SubagentRun[]>([])'), 'Runs view should keep active subagents')
assert.ok(runsView.includes("t('runs.activeSubagents')"), 'Runs view should render active subagents section')
