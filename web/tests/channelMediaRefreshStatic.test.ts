import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/media.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/ChannelsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes('refreshMedia'), 'media API should expose refreshMedia')
assert.ok(api.includes('/api/media/${encodeURIComponent(mediaId)}/refresh'), 'refreshMedia should call backend refresh endpoint')
assert.ok(view.includes('refreshSelectedMedia'), 'channels media detail should refresh selected media')
assert.ok(view.includes("t('channels.mediaRefreshDetail')"), 'channels media drawer should render refresh detail action')
assert.ok(zh.includes("mediaRefreshDetail: '刷新详情'"), 'Chinese locale should include detail refresh label')
assert.ok(en.includes("mediaRefreshDetail: 'Refresh detail'"), 'English locale should include detail refresh label')
