import assert from 'node:assert/strict'
import { createI18n } from 'vue-i18n'
import en from '../src/i18n/locales/en.ts'
import zh from '../src/i18n/locales/zh.ts'

const messages = { en, zh }

for (const locale of ['zh', 'en'] as const) {
  const i18n = createI18n({
    legacy: false,
    locale,
    messages,
  })
  const text = i18n.global.t('mcp.placeholders.toolsJson')

  assert.equal(
    text,
    locale === 'zh'
      ? '[{"name":"docs_search","description":"检索文档"}]'
      : '[{"name":"docs_search","description":"Search documents"}]',
  )
}
