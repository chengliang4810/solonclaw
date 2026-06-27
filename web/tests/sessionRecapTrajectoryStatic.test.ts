import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const sessionsApi = readFileSync(new URL('../src/api/solonclaw/sessions.ts', import.meta.url), 'utf8')
const runsView = readFileSync(new URL('../src/views/solonclaw/RunsView.vue', import.meta.url), 'utf8')

assert.ok(sessionsApi.includes('/recap?limit='), 'session recap endpoint should be wrapped')
assert.ok(sessionsApi.includes('/trajectory?'), 'session trajectory endpoint should be wrapped')
assert.ok(sessionsApi.includes('/trajectory/save?'), 'session trajectory save endpoint should be wrapped')
assert.ok(runsView.includes('fetchSessionRecap'), 'Runs view should load session recap')
assert.ok(runsView.includes('fetchSessionTrajectory'), 'Runs view should load session trajectory')
assert.ok(runsView.includes('saveSessionTrajectory'), 'Runs view should save trajectory on user action')
assert.ok(runsView.includes("t('runs.sessionRecap')"), 'Runs view should render recap')
assert.ok(runsView.includes("t('runs.sessionTrajectory')"), 'Runs view should render trajectory')
