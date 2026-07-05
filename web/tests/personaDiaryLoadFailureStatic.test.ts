import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const view = readFileSync(new URL('../src/views/solonclaw/PersonaDiaryView.vue', import.meta.url), 'utf8')

assert.ok(view.includes('const loadError = ref<string | null>(null)'), 'PersonaDiaryView should keep a visible load error')
assert.ok(view.includes('loadError.value = null'), 'PersonaDiaryView should clear stale load errors before retrying')
assert.ok(view.includes('loadError.value ='), 'PersonaDiaryView should preserve the failed request message')
assert.ok(view.includes("t('personaDiary.loadFailed')"), 'PersonaDiaryView should render the localized diary load failure label')
assert.ok(view.includes('v-if="loadError"'), 'PersonaDiaryView should render the diary load error')
assert.ok(view.includes('class="diary-load-error"'), 'PersonaDiaryView should use a persistent inline error block')
assert.ok(!view.includes('console.error'), 'PersonaDiaryView should not rely on console-only load failures')
