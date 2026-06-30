import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const i18nIndex = readFileSync(new URL('../src/i18n/index.ts', import.meta.url), 'utf8')
const languageSwitch = readFileSync(new URL('../src/components/layout/LanguageSwitch.vue', import.meta.url), 'utf8')

for (const locale of ['zh', 'en', 'ja', 'ko', 'fr', 'es', 'de', 'pt']) {
  assert.ok(
    languageSwitch.includes(`value: '${locale}'`),
    `language switch should expose ${locale}`,
  )
  assert.ok(
    i18nIndex.includes(`import ${locale} from './locales/${locale}'`),
    `i18n should import ${locale} locale messages`,
  )
  assert.ok(
    i18nIndex.includes(locale === 'pt' ? 'messages: { zh, en, ja, ko, fr, es, de, pt }' : `${locale},`),
    `i18n should register ${locale} locale messages`,
  )
}
