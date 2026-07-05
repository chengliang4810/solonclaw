import { createI18n } from 'vue-i18n'
import de from './locales/de'
import en from './locales/en'
import es from './locales/es'
import fr from './locales/fr'
import ja from './locales/ja'
import ko from './locales/ko'
import pt from './locales/pt'
import zh from './locales/zh'

const supportedLocales = ['zh', 'en', 'ja', 'ko', 'fr', 'es', 'de', 'pt'] as const
type SupportedLocale = typeof supportedLocales[number]

function initialLocale(): SupportedLocale {
  const saved = typeof localStorage === 'undefined' ? null : localStorage.getItem('solonclaw_locale')
  return supportedLocales.includes(saved as SupportedLocale) ? (saved as SupportedLocale) : 'zh'
}

export const i18n = createI18n({
  legacy: false,
  locale: initialLocale(),
  fallbackLocale: 'zh',
  messages: { zh, en, ja, ko, fr, es, de, pt },
})
