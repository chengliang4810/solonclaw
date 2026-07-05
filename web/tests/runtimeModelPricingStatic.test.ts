import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/system.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/ModelsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes('pricing?: ModelPricingStatus'), 'runtime model type should expose backend pricing payload')
assert.ok(!api.includes('input_price?:'), 'runtime model type should not rely on stale input_price field')
assert.ok(!api.includes('output_price?:'), 'runtime model type should not rely on stale output_price field')
assert.ok(view.includes('model.pricing?.input'), 'models view should render backend pricing input field')
assert.ok(view.includes('model.pricing?.output'), 'models view should render backend pricing output field')
assert.ok(view.includes("t('models.cachePrice'"), 'models view should render cache/reasoning pricing')
assert.ok(zh.includes("cachePrice: '缓存读 {cacheRead} / 写 {cacheWrite} / 推理 {reasoning}'"), 'Chinese locale should include cache pricing label')
assert.ok(en.includes("cachePrice: 'Cache read {cacheRead} / write {cacheWrite} / reasoning {reasoning}'"), 'English locale should include cache pricing label')
