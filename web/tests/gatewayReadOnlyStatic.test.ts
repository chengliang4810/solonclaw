import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const view = readFileSync(new URL('../src/views/solonclaw/GatewaysView.vue', import.meta.url), 'utf8')
const store = readFileSync(new URL('../src/stores/solonclaw/gateways.ts', import.meta.url), 'utf8')
const api = readFileSync(new URL('../src/api/solonclaw/gateways.ts', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')

assert.ok(view.includes('gatewayStore.fetchStatus()'), 'gateway page should keep status refresh')
assert.ok(!view.includes('handleToggle'), 'gateway page should not render unsupported start/stop actions')
assert.ok(!view.includes("from 'antdv-next'") || !view.includes('Button'), 'gateway page should not import a control button for unsupported actions')
assert.ok(!store.includes('startGateway'), 'gateway store should not expose unsupported start action')
assert.ok(!store.includes('stopGateway'), 'gateway store should not expose unsupported stop action')
assert.ok(!api.includes('startGateway'), 'gateway API should not keep a fake start wrapper')
assert.ok(!api.includes('stopGateway'), 'gateway API should not keep a fake stop wrapper')
assert.ok(!zh.includes('查看和控制各消息网关'), 'gateway copy should not imply process control')
