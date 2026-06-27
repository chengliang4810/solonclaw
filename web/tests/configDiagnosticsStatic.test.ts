import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'

const configApi = readFileSync(new URL('../src/api/solonclaw/config.ts', import.meta.url), 'utf8')
const settingsView = readFileSync(new URL('../src/views/solonclaw/SettingsView.vue', import.meta.url), 'utf8')
const componentUrl = new URL('../src/components/solonclaw/settings/ConfigDiagnosticsSettings.vue', import.meta.url)

assert.ok(configApi.includes('/api/config/diagnostics'), 'config diagnostics endpoint should be wrapped')
assert.ok(existsSync(componentUrl), 'config diagnostics settings component should exist')
assert.ok(settingsView.includes('ConfigDiagnosticsSettings'), 'Settings view should expose config diagnostics tab')
