import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/diagnostics.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes('fetchPlatformToolsets'), 'diagnostics API should expose platform toolsets fetch')
assert.ok(api.includes("'/api/tools/platform-toolsets'"), 'platform toolsets fetch should call backend endpoint')
assert.ok(view.includes('platformToolsets.value = await fetchPlatformToolsets()'), 'diagnostics view should load platform toolsets')
assert.ok(view.includes("t('diagnostics.platformToolsets')"), 'diagnostics view should render platform toolsets panel')
assert.ok(view.includes('platformToolsetRows'), 'diagnostics view should normalize platform toolset rows')
assert.ok(zh.includes("platformToolsets: '平台工具集'"), 'Chinese locale should include platform toolsets label')
assert.ok(en.includes("platformToolsets: 'Platform toolsets'"), 'English locale should include platform toolsets label')
