import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const systemApi = readFileSync(new URL('../src/api/solonclaw/system.ts', import.meta.url), 'utf8')
const appStore = readFileSync(new URL('../src/stores/solonclaw/app.ts', import.meta.url), 'utf8')
const sidebar = readFileSync(new URL('../src/components/layout/AppSidebar.vue', import.meta.url), 'utf8')
const locales = ['zh', 'en', 'ja', 'ko', 'es', 'pt', 'de', 'fr']
  .map(locale => readFileSync(new URL(`../src/i18n/locales/${locale}.ts`, import.meta.url), 'utf8'))
  .join('\n')

for (const source of [systemApi, appStore, sidebar, locales]) {
  assert.equal(source.includes('triggerUpdate'), false)
  assert.equal(source.includes('doUpdate'), false)
  assert.equal(source.includes('updateAvailable'), false)
  assert.equal(source.includes('handleUpdate'), false)
  assert.equal(source.includes('updateVersion'), false)
  assert.equal(source.includes('updateSuccess'), false)
  assert.equal(source.includes('updateFailed'), false)
}
