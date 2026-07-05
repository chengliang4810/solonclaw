import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const sidebar = readFileSync(new URL('../src/components/layout/AppSidebar.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(sidebar.includes('appStore.serverVersion'), 'sidebar should render server version from /api/status')
assert.ok(sidebar.includes('t("sidebar.version")'), 'sidebar should label the version')
assert.ok(zh.includes("version: '版本'"), 'zh sidebar copy should include version label')
assert.ok(en.includes("version: 'Version'"), 'en sidebar copy should include version label')
