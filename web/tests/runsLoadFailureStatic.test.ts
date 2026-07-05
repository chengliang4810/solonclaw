import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const view = readFileSync(new URL('../src/views/solonclaw/RunsView.vue', import.meta.url), 'utf8')

assert.ok(view.includes('const loadError = ref<string | null>(null)'), 'RunsView should keep a visible session load error')
assert.ok(view.includes('const recoverableError = ref<string | null>(null)'), 'RunsView should keep a visible recoverable load error')
assert.ok(view.includes('loadError.value = null'), 'loadSessionDetail should clear stale errors before retrying')
assert.ok(view.includes('recoverableError.value = null'), 'loadRecoverableRuns should clear stale errors before retrying')
assert.ok(view.includes('loadError.value ='), 'loadSessionDetail should preserve the failed request message')
assert.ok(view.includes('recoverableError.value ='), 'loadRecoverableRuns should preserve the failed request message')
assert.ok(view.includes('v-if="loadError"'), 'RunsView should render the session load error branch')
assert.ok(view.includes('v-if="recoverableError"'), 'RunsView should render the recoverable load error branch')

const sessionErrorIndex = view.indexOf('loadError')
const noRunsIndex = view.indexOf("t('runs.noRuns')")
assert.ok(sessionErrorIndex >= 0, 'RunsView should render session load failures')
assert.ok(noRunsIndex >= 0, 'RunsView should still keep the run-list empty state')
assert.ok(sessionErrorIndex < noRunsIndex, 'RunsView should show session load failures before no-runs')

const recoverableErrorIndex = view.indexOf('recoverableError')
const noRecoverableIndex = view.indexOf("t('runs.noRecoverableRuns')")
assert.ok(recoverableErrorIndex >= 0, 'RunsView should render recoverable load failures')
assert.ok(noRecoverableIndex >= 0, 'RunsView should still keep the recoverable empty state')
assert.ok(recoverableErrorIndex < noRecoverableIndex, 'RunsView should show recoverable failures before no-recoverable-runs')
assert.ok(view.includes("t('common.fetchFailed')"), 'RunsView should use the common fetch failure label')
