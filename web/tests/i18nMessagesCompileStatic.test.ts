import assert from 'node:assert/strict'
import { createI18n } from 'vue-i18n'
import de from '../src/i18n/locales/de.ts'
import en from '../src/i18n/locales/en.ts'
import es from '../src/i18n/locales/es.ts'
import fr from '../src/i18n/locales/fr.ts'
import ja from '../src/i18n/locales/ja.ts'
import ko from '../src/i18n/locales/ko.ts'
import pt from '../src/i18n/locales/pt.ts'
import zh from '../src/i18n/locales/zh.ts'

const locales = { zh, en, ja, ko, fr, es, de, pt }

function messageKeys(value: unknown, prefix = ''): string[] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return []
  }
  return Object.entries(value).flatMap(([key, child]) => {
    const path = prefix ? `${prefix}.${key}` : key
    return child && typeof child === 'object' && !Array.isArray(child)
      ? messageKeys(child, path)
      : [path]
  })
}

for (const [locale, messages] of Object.entries(locales)) {
  const i18n = createI18n({
    legacy: false,
    locale,
    messages: { [locale]: messages },
  })
  const errors: string[] = []

  for (const key of messageKeys(messages)) {
    try {
      i18n.global.t(key)
    } catch (error) {
      errors.push(`${locale}.${key}: ${(error as Error).message.split('\n')[0]}`)
    }
  }

  assert.deepEqual(errors, [])
}
