import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const sessionsApi = readFileSync(new URL('../src/api/solonclaw/sessions.ts', import.meta.url), 'utf8')
const runsView = readFileSync(new URL('../src/views/solonclaw/RunsView.vue', import.meta.url), 'utf8')

assert.ok(sessionsApi.includes('/api/checkpoints/${id}/preview'), 'checkpoint preview endpoint should be wrapped')
assert.ok(sessionsApi.includes('fetchCheckpointPreview'), 'checkpoint preview wrapper should be exported')
assert.ok(runsView.includes('fetchCheckpointPreview'), 'Runs view should call checkpoint preview API')
assert.ok(runsView.includes('checkpointPreview = ref'), 'Runs view should keep checkpoint preview')
assert.ok(runsView.includes('handleCheckpointPreview'), 'Runs view should expose checkpoint preview action')
assert.ok(runsView.includes("t('runs.checkpointPreview')"), 'Runs view should render checkpoint preview result')
