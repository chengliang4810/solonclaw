import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const configApi = readFileSync(new URL('../src/api/solonclaw/config.ts', import.meta.url), 'utf8')
const configDiagnostics = readFileSync(new URL('../src/components/solonclaw/settings/ConfigDiagnosticsSettings.vue', import.meta.url), 'utf8')

assert.ok(configApi.includes('/api/config/raw'), 'raw config endpoint should be wrapped')
assert.ok(configApi.includes('fetchRawConfig'), 'raw config wrapper should be exported')
assert.ok(configDiagnostics.includes('fetchRawConfig'), 'Config diagnostics view should load raw config')
assert.ok(configDiagnostics.includes('rawConfig = ref'), 'Config diagnostics view should keep raw config')
assert.ok(configDiagnostics.includes("t('settings.configDiagnostics.rawTitle')"), 'Config diagnostics view should render raw config section')
