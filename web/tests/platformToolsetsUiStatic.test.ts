import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/diagnostics.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes('fetchPlatformToolsets'), 'diagnostics API should expose platform toolsets fetch')
assert.ok(api.includes("'/api/tools/platform-toolsets'"), 'platform toolsets fetch should call backend endpoint')
assert.ok(api.includes('updatePlatformToolsets'), 'diagnostics API should expose platform toolsets update')
assert.ok(api.includes("`/api/tools/platform-toolsets/${encodeURIComponent(platform)}`"), 'platform toolsets update should call platform endpoint')
assert.ok(api.includes("method: 'PUT'"), 'platform toolsets update should use PUT')
assert.ok(view.includes('platformToolsets.value = await fetchPlatformToolsets()'), 'diagnostics view should load platform toolsets')
assert.ok(view.includes("t('diagnostics.platformToolsets')"), 'diagnostics view should render platform toolsets panel')
assert.ok(view.includes('platformToolsetRows'), 'diagnostics view should normalize platform toolset rows')
assert.ok(view.includes('savePlatformToolsets'), 'diagnostics view should save platform toolsets')
assert.ok(view.includes('platformToolsetForms[row.platform]'), 'diagnostics view should bind editable platform toolsets forms')
assert.ok(view.includes("t('diagnostics.savePlatformToolsets')"), 'diagnostics view should render platform toolsets save action')
assert.ok(zh.includes("platformToolsets: '平台工具集'"), 'Chinese locale should include platform toolsets label')
assert.ok(en.includes("platformToolsets: 'Platform toolsets'"), 'English locale should include platform toolsets label')
assert.ok(zh.includes("savePlatformToolsets: '保存工具集'"), 'Chinese locale should include platform toolsets save label')
assert.ok(en.includes("savePlatformToolsets: 'Save toolsets'"), 'English locale should include platform toolsets save label')
