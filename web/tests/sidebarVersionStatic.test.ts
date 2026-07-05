import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const sidebar = readFileSync(new URL('../src/components/layout/AppSidebar.vue', import.meta.url), 'utf8')
const systemApi = readFileSync(new URL('../src/api/solonclaw/system.ts', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(sidebar.includes('appStore.serverVersion'), 'sidebar should render server version from /api/status')
assert.ok(sidebar.includes('t("sidebar.version")'), 'sidebar should label the version')
assert.ok(sidebar.includes('appStore.latestVersion !== appStore.serverVersion'), 'sidebar should show latest version only when different')
assert.ok(sidebar.includes('t("sidebar.latestVersion")'), 'sidebar should label the latest version')
assert.ok(systemApi.includes('latest_version'), 'status API should consume latest_version')
assert.ok(systemApi.includes('latest_tag'), 'status API should fall back to latest_tag')
assert.ok(zh.includes("version: '版本'"), 'zh sidebar copy should include version label')
assert.ok(zh.includes("latestVersion: '最新'"), 'zh sidebar copy should include latest version label')
assert.ok(en.includes("version: 'Version'"), 'en sidebar copy should include version label')
assert.ok(en.includes("latestVersion: 'Latest'"), 'en sidebar copy should include latest version label')
