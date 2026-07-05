import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const store = readFileSync(new URL('../src/stores/solonclaw/settings.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/SettingsView.vue', import.meta.url), 'utf8')

assert.ok(store.includes('const loadError = ref<string | null>(null)'), 'settings store should keep a visible settings load error')
assert.ok(store.includes('loadError.value = null'), 'fetchSettings should clear stale settings load errors before retrying')
assert.ok(store.includes('loadError.value ='), 'fetchSettings should preserve the failed request message')
assert.ok(store.includes('loadError,'), 'settings store should expose load errors')

const errorIndex = view.indexOf('settingsStore.loadError')
const tabsIndex = view.indexOf('<Tabs')
assert.ok(errorIndex >= 0, 'SettingsView should render persistent settings load failures')
assert.ok(tabsIndex >= 0, 'SettingsView should still render settings tabs')
assert.ok(errorIndex < tabsIndex, 'SettingsView should show load failures before settings tabs')
assert.ok(view.includes("t('common.fetchFailed')"), 'SettingsView should use the common fetch failure label')
