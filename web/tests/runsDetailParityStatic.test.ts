import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const runsApi = readFileSync(new URL('../src/api/solonclaw/runs.ts', import.meta.url), 'utf8')
const runsView = readFileSync(new URL('../src/views/solonclaw/RunsView.vue', import.meta.url), 'utf8')

assert.ok(runsApi.includes('interface RunControlCommand'), 'run commands should be typed')
assert.ok(runsApi.includes('commands: RunControlCommand[]'), 'run detail should expose commands from backend detail')
assert.ok(runsView.includes('const recoveries = ref<RunRecovery[]>([])'), 'Runs view should keep recoveries from detail')
assert.ok(runsView.includes('const commands = ref<RunControlCommand[]>([])'), 'Runs view should keep commands from detail')
assert.ok(runsView.includes('detail.recoveries'), 'Runs view should read recoveries from run detail')
assert.ok(runsView.includes('detail.commands'), 'Runs view should read commands from run detail')
assert.ok(runsView.includes("t('runs.recoveries')"), 'Runs view should render recoveries section')
assert.ok(runsView.includes("t('runs.commands')"), 'Runs view should render commands section')
