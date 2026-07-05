import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'

const configApiFile = new URL('../src/api/solonclaw/config.ts', import.meta.url)
const settingsStoreFile = new URL('../src/stores/solonclaw/settings.ts', import.meta.url)
const settingsViewFile = new URL('../src/views/solonclaw/SettingsView.vue', import.meta.url)
const gatewaySettingsFile = new URL('../src/components/solonclaw/settings/GatewaySettings.vue', import.meta.url)
const zhFile = new URL('../src/i18n/locales/zh.ts', import.meta.url)
const enFile = new URL('../src/i18n/locales/en.ts', import.meta.url)

const configApi = readFileSync(configApiFile, 'utf8')
const settingsStore = readFileSync(settingsStoreFile, 'utf8')
const settingsView = readFileSync(settingsViewFile, 'utf8')
const zh = readFileSync(zhFile, 'utf8')
const en = readFileSync(enFile, 'utf8')

assert.ok(configApi.includes('export interface GatewayConfig'), 'config API should expose GatewayConfig')
assert.ok(configApi.includes('gateway?: GatewayConfig'), 'AppConfig should include gateway config')
assert.ok(
  configApi.includes('processingReactionsEnabled: configBoolean(data.gateway?.processingReactionsEnabled)'),
  'fetchConfig should map gateway.processingReactionsEnabled from backend config',
)
assert.ok(configApi.includes("section === 'gateway'"), 'updateConfigSection should save gateway config')
assert.ok(
  configApi.includes('processingReactionsEnabled: values.processingReactionsEnabled ?? next.gateway?.processingReactionsEnabled'),
  'gateway config save should preserve existing processingReactionsEnabled when omitted',
)

assert.ok(settingsStore.includes('GatewayConfig'), 'settings store should type gateway config')
assert.ok(settingsStore.includes('const gateway = ref<GatewayConfig>({})'), 'settings store should hold gateway config')
assert.ok(settingsStore.includes('gateway.value = data.gateway || {}'), 'settings store should load gateway config')
assert.ok(settingsStore.includes("case 'gateway'"), 'settings store should merge saved gateway config')

assert.ok(existsSync(gatewaySettingsFile), 'settings should provide a GatewaySettings component')
assert.ok(settingsView.includes('GatewaySettings'), 'Settings view should mount GatewaySettings')
assert.ok(settingsView.includes('tabKey="gateway"'), 'Settings view should include a gateway tab')
assert.ok(settingsView.includes("settings.tabs.gateway"), 'Settings gateway tab should be localized')

assert.ok(zh.includes("gateway: '网关'"), 'Chinese locale should include gateway settings tab')
assert.ok(en.includes("gateway: 'Gateway'"), 'English locale should include gateway settings tab')
assert.ok(zh.includes("processingReactionsEnabled: '处理状态表情回应'"), 'Chinese locale should label processing reactions')
assert.ok(en.includes("processingReactionsEnabled: 'Processing status reactions'"), 'English locale should label processing reactions')
