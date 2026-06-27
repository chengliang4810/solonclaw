import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/media.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/ChannelsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes('/api/media/${encodeURIComponent(mediaId)}'), 'media detail endpoint should be wrapped')
assert.ok(api.includes('fetchMediaDetail'), 'media detail wrapper should be exported')
assert.ok(view.includes('fetchMediaDetail'), 'channels page should load media detail')
assert.ok(view.includes('mediaDetailOpen'), 'channels page should show media detail drawer')
assert.ok(view.includes('selectedMediaDetail'), 'channels page should retain selected media detail')
assert.ok(zh.includes('媒体缓存'), 'Chinese locale should label media cache')
assert.ok(en.includes('Media cache'), 'English locale should label media cache')
