import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const view = readFileSync(new URL('../src/views/solonclaw/MemoryView.vue', import.meta.url), 'utf8')

assert.ok(view.includes('const loadError = ref<string | null>(null)'), 'MemoryView should keep a visible load error')
assert.ok(view.includes('loadError.value = null'), 'loadMemory should clear stale errors before retrying')
assert.ok(view.includes('loadError.value ='), 'loadMemory should preserve the failed request message')
assert.ok(view.includes('v-if="loadError"'), 'MemoryView should render the memory load error')
assert.ok(view.includes("t('memory.loadFailed')"), 'MemoryView should render the localized memory load failure label')
assert.ok(view.includes('v-if="!loadError || data"'), 'MemoryView should not show the empty document panel on initial load failure')
assert.ok(!view.includes("message.error(t('memory.loadFailed'))"), 'MemoryView should not rely on toast-only memory load failures')
