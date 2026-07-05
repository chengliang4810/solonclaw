import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const store = readFileSync(new URL('../src/stores/solonclaw/jobs.ts', import.meta.url), 'utf8')
const panel = readFileSync(new URL('../src/components/solonclaw/jobs/JobsPanel.vue', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/JobsView.vue', import.meta.url), 'utf8')

assert.ok(store.includes('const loadError = ref<string | null>(null)'), 'jobs store should keep a visible jobs load error')
assert.ok(store.includes('const upcomingError = ref<string | null>(null)'), 'jobs store should keep a visible upcoming load error')
assert.ok(store.includes('const statusError = ref<string | null>(null)'), 'jobs store should keep a visible cron status error')
assert.ok(store.includes('loadError.value = null'), 'fetchJobs should clear stale jobs errors before retrying')
assert.ok(store.includes('upcomingError.value = null'), 'fetchUpcomingJobs should clear stale upcoming errors before retrying')
assert.ok(store.includes('statusError.value = null'), 'fetchStatus should clear stale status errors before retrying')

const errorIndex = panel.indexOf('jobsStore.loadError')
const emptyIndex = panel.indexOf('jobsStore.jobs.length === 0')
assert.ok(errorIndex >= 0, 'JobsPanel should render the persistent jobs load error')
assert.ok(emptyIndex >= 0, 'JobsPanel should still keep an empty state')
assert.ok(errorIndex < emptyIndex, 'JobsPanel should show the load error before the empty state')
assert.ok(panel.includes('jobsStore.upcomingError'), 'JobsPanel should render upcoming load failures')

assert.ok(view.includes('onUnmounted'), 'JobsView should clean up its refresh timer')
assert.ok(view.includes('setInterval(refreshSchedules'), 'JobsView should auto-refresh schedule data')
assert.ok(view.includes('clearInterval'), 'JobsView should clear the schedule refresh timer on unmount')
assert.ok(view.includes('jobsStore.statusError'), 'JobsView should render cron status load failures')
assert.ok(view.includes('jobsStore.guideError'), 'JobsView should render cron guide load failures')
