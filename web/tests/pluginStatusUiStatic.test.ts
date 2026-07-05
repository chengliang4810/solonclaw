import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/diagnostics.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes('fetchPluginStatus'), 'diagnostics API should expose plugin status fetch')
assert.ok(api.includes("'/api/plugins/status'"), 'plugin status fetch should call backend endpoint')
assert.ok(view.includes('fetchPluginStatus()'), 'diagnostics view should request plugin status')
assert.ok(view.includes('pluginStatus.value = pluginStatusData'), 'diagnostics view should store plugin status response')
assert.ok(view.includes("t('diagnostics.pluginStatus')"), 'diagnostics view should render plugin status panel')
assert.ok(view.includes('pluginDiagnostics'), 'diagnostics view should normalize plugin diagnostics')
assert.ok(view.includes('pluginLoadedCount'), 'diagnostics view should display loaded plugin count')
assert.ok(zh.includes("pluginStatus: '插件状态'"), 'Chinese locale should include plugin status label')
assert.ok(en.includes("pluginStatus: 'Plugin status'"), 'English locale should include plugin status label')
