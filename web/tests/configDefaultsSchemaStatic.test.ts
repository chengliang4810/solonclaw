import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const configApi = readFileSync(new URL('../src/api/solonclaw/config.ts', import.meta.url), 'utf8')
const configDiagnostics = readFileSync(new URL('../src/components/solonclaw/settings/ConfigDiagnosticsSettings.vue', import.meta.url), 'utf8')

assert.ok(configApi.includes('/api/config/defaults'), 'config defaults endpoint should be wrapped')
assert.ok(configApi.includes('/api/config/schema'), 'config schema endpoint should be wrapped')
assert.ok(configApi.includes('fetchConfigDefaults'), 'config defaults wrapper should be exported')
assert.ok(configApi.includes('fetchConfigSchema'), 'config schema wrapper should be exported')
assert.ok(configDiagnostics.includes('fetchConfigDefaults'), 'Config diagnostics view should load defaults')
assert.ok(configDiagnostics.includes('fetchConfigSchema'), 'Config diagnostics view should load schema')
assert.ok(configDiagnostics.includes("t('settings.configDiagnostics.defaultsTitle')"), 'Config diagnostics view should render defaults')
assert.ok(configDiagnostics.includes("t('settings.configDiagnostics.schemaTitle')"), 'Config diagnostics view should render schema')
