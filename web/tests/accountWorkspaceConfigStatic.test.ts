import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const account = readFileSync(new URL('../src/components/solonclaw/settings/AccountSettings.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(account.includes('workspaceConfigItems'), 'account settings should keep all workspace config items')
assert.ok(account.includes('Object.entries(items)'), 'account settings should derive rows from /api/workspace-config')
assert.ok(account.includes('item.redacted_value'), 'account settings should render redacted values only')
assert.ok(account.includes('t("account.workspaceConfigItems")'), 'account settings should label workspace config list')
assert.ok(zh.includes("workspaceConfigItems: '工作区配置项'"), 'zh copy should include workspace config list label')
assert.ok(en.includes("workspaceConfigItems: 'Workspace config items'"), 'en copy should include workspace config list label')
