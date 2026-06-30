import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const router = readFileSync(new URL('../src/router/index.ts', import.meta.url), 'utf8')
const sidebar = readFileSync(new URL('../src/components/layout/AppSidebar.vue', import.meta.url), 'utf8')
const systemNav = readFileSync(new URL('../src/components/layout/SystemNavItems.vue', import.meta.url), 'utf8')
const api = readFileSync(new URL('../src/api/solonclaw/curator.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/CuratorView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes('/api/curator'), 'curator API should call backend curator endpoints')
assert.ok(router.includes("name: 'solonclaw.curator'"), 'router should expose curator page')
assert.ok(sidebar.includes('SystemNavItems'), 'sidebar should delegate system navigation entries')
assert.ok(systemNav.includes("key: 'solonclaw.curator'"), 'system navigation catalog should link to curator page')
assert.ok(view.includes('fetchCuratorReports'), 'curator view should load report list')
assert.ok(view.includes('runCurator'), 'curator view should expose manual run action')
assert.ok(view.includes('fetchCuratorReport'), 'curator view should load selected report detail')
assert.ok(api.includes('/api/curator/improvements'), 'curator API should expose improvement suggestions')
assert.ok(api.includes('/api/curator/${action}'), 'curator API should expose suggestion state actions')
assert.ok(view.includes('fetchCuratorImprovements'), 'curator view should load improvement suggestions')
assert.ok(view.includes('markCuratorSuggestion'), 'curator view should allow marking improvement suggestions')
assert.ok(zh.includes("curator: '技能维护'"), 'Chinese sidebar locale should include curator label')
assert.ok(en.includes("curator: 'Curator'"), 'English sidebar locale should include curator label')
