import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import de from '../src/i18n/locales/de.ts'
import en from '../src/i18n/locales/en.ts'
import es from '../src/i18n/locales/es.ts'
import fr from '../src/i18n/locales/fr.ts'
import ja from '../src/i18n/locales/ja.ts'
import ko from '../src/i18n/locales/ko.ts'
import pt from '../src/i18n/locales/pt.ts'
import zh from '../src/i18n/locales/zh.ts'

const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')

assert.ok(app.includes("t('sidebar.nodeVersionWarning'"))

for (const [locale, messages] of Object.entries({ de, en, es, fr, ja, ko, pt, zh })) {
  assert.equal(
    typeof messages.sidebar.nodeVersionWarning,
    'string',
    `${locale} should translate sidebar.nodeVersionWarning`,
  )
  assert.match(messages.sidebar.nodeVersionWarning, /\{version\}/)
}
