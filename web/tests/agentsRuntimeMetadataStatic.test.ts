import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/agents.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/AgentsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes('last_used_at?: number'), 'agents API should expose backend last used timestamp')
assert.ok(api.includes('updated_at?: number'), 'agents API should expose backend updated timestamp')
assert.ok(view.includes('selectedAgent?.last_used_at'), 'agents view should render last used timestamp')
assert.ok(view.includes('selectedAgent?.updated_at'), 'agents view should render updated timestamp')
assert.ok(zh.includes("lastUsedAt: '最近使用'"), 'Chinese locale should include last used label')
assert.ok(zh.includes("updatedAt: '最近更新'"), 'Chinese locale should include updated label')
assert.ok(en.includes("lastUsedAt: 'Last used'"), 'English locale should include last used label')
assert.ok(en.includes("updatedAt: 'Updated'"), 'English locale should include updated label')
