import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const systemApi = readFileSync(new URL('../src/api/solonclaw/system.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(systemApi.includes('fetchRuntimeStatus'), 'system API should expose runtime status fetch')
assert.ok(systemApi.includes('runtime_status'), 'runtime status fetch should consume backend runtime_status payload')
assert.ok(view.includes('fetchRuntimeStatus'), 'diagnostics view should load runtime status')
assert.ok(view.includes("t('diagnostics.runtimeCapabilities')"), 'diagnostics view should render runtime capabilities panel')
assert.ok(view.includes('runtimeCapabilityRows'), 'diagnostics view should normalize runtime capability rows')
assert.ok(view.includes("runtime_status?.multimodal"), 'diagnostics view should display multimodal status')
assert.ok(view.includes("runtime_status?.pricing"), 'diagnostics view should display pricing status')
assert.ok(view.includes("runtime_status?.gateway"), 'diagnostics view should display gateway runtime status')
assert.ok(zh.includes("runtimeCapabilities: '运行能力'"), 'Chinese locale should include runtime capabilities label')
assert.ok(zh.includes("runtimeGateway: '消息网关'"), 'Chinese locale should include runtime gateway label')
assert.ok(en.includes("runtimeCapabilities: 'Runtime capabilities'"), 'English locale should include runtime capabilities label')
assert.ok(en.includes("runtimeGateway: 'Gateway'"), 'English locale should include runtime gateway label')
