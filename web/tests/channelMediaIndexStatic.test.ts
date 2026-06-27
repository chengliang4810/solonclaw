import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/media.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/ChannelsView.vue', import.meta.url), 'utf8')

assert.ok(api.includes('indexMedia'), 'media API should expose indexMedia')
assert.ok(api.includes("request('/api/media/index'"), 'indexMedia should call backend index endpoint')
assert.ok(view.includes('indexMedia'), 'channels page should import and call indexMedia')
assert.ok(view.includes('mediaIndexPath'), 'channels page should bind a local path input')
assert.ok(view.includes('handleIndexMedia'), 'channels page should expose a local media index action')
assert.ok(view.includes("indexMedia({ localPath })"), 'channels page should submit localPath to the API')
assert.ok(view.includes("t('channels.mediaIndexLocal')"), 'channels page should render the index action label')

for (const locale of ['zh', 'en', 'ja', 'ko', 'pt', 'fr', 'de', 'es']) {
  const content = readFileSync(new URL(`../src/i18n/locales/${locale}.ts`, import.meta.url), 'utf8')
  for (const key of ['mediaIndexLocal', 'mediaIndexPlaceholder', 'mediaIndexReady', 'mediaIndexFailed']) {
    assert.ok(content.includes(`${key}:`), `${locale} locale should define channels.${key}`)
  }
}
