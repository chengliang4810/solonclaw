import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const runsView = readFileSync(new URL('../src/views/solonclaw/RunsView.vue', import.meta.url), 'utf8')

assert.ok(runsView.includes('fetchRecoverableRuns'), 'Runs view should import recoverable runs API')
assert.ok(runsView.includes('const recoverableRuns = ref<AgentRun[]>([])'), 'Runs view should keep recoverable runs')
assert.ok(runsView.includes('loadRecoverableRuns'), 'Runs view should expose a recoverable runs loader')
assert.ok(runsView.includes("t('runs.recoverableRuns')"), 'Runs view should render recoverable runs section')
