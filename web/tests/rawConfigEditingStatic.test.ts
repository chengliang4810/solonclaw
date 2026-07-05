import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/config.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/SettingsView.vue', import.meta.url), 'utf8')

assert.ok(api.includes('saveRawConfig'), 'config API should expose raw config save')
assert.ok(api.includes("'/api/config/raw'"), 'raw config save should use backend raw endpoint')
assert.ok(api.includes("method: 'PUT'"), 'raw config save should PUT updated YAML')
assert.ok(api.includes('yaml_text'), 'raw config save should use backend yaml_text field')
assert.ok(view.includes('rawConfigText'), 'settings view should keep editable raw config text')
assert.ok(view.includes('saveRawConfig('), 'settings view should save raw config edits')
assert.ok(view.includes('t("settings.configDiagnostics.saveRaw")'), 'settings view should render save action')
