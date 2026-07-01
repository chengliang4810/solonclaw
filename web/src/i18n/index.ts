import { createI18n } from 'vue-i18n'
import de from './locales/de'
import en from './locales/en'
import es from './locales/es'
import fr from './locales/fr'
import ja from './locales/ja'
import ko from './locales/ko'
import pt from './locales/pt'
import zh from './locales/zh'

export const i18n = createI18n({
  legacy: false,
  locale: 'zh',
  fallbackLocale: 'zh',
  messages: { zh, en, ja, ko, fr, es, de, pt },
})
