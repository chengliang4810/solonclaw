import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const store = readFileSync(new URL('../src/stores/solonclaw/usage.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/UsageView.vue', import.meta.url), 'utf8')

assert.ok(store.includes('const loadError = ref<string | null>(null)'), 'usage store should keep a visible load error')
assert.ok(store.includes('loadError.value = null'), 'loadUsage should clear stale errors before retrying')
assert.ok(store.includes('loadError.value ='), 'loadUsage should preserve the failed request message')
assert.ok(store.includes('loadError,'), 'usage store should expose the load error to the view')

const errorIndex = view.indexOf('usageStore.loadError')
const emptyIndex = view.indexOf("t('usage.noData')")
assert.ok(errorIndex >= 0, 'UsageView should render the persistent usage load error')
assert.ok(emptyIndex >= 0, 'UsageView should still keep the empty state')
assert.ok(errorIndex < emptyIndex, 'UsageView should show load failures before the no-data state')
assert.ok(view.includes("t('common.fetchFailed')"), 'UsageView should use the common fetch failure label')
