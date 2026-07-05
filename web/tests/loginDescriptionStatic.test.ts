import assert from 'node:assert/strict'

import de from '../src/i18n/locales/de.ts'
import en from '../src/i18n/locales/en.ts'
import es from '../src/i18n/locales/es.ts'
import fr from '../src/i18n/locales/fr.ts'
import ja from '../src/i18n/locales/ja.ts'
import ko from '../src/i18n/locales/ko.ts'
import pt from '../src/i18n/locales/pt.ts'
import zh from '../src/i18n/locales/zh.ts'

const locales = { de, en, es, fr, ja, ko, pt, zh }

for (const [locale, messages] of Object.entries(locales)) {
  const description = messages.login.description
  assert.ok(
    description.includes('solonclaw.dashboard.accessToken'),
    `${locale} login description should point users to the dashboard access token config key`,
  )
}
