import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const router = readFileSync(new URL('../src/router/index.ts', import.meta.url), 'utf8')
const sidebar = readFileSync(new URL('../src/components/layout/AppSidebar.vue', import.meta.url), 'utf8')
const api = readFileSync(new URL('../src/api/solonclaw/curator.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/CuratorView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes('/api/curator'), 'curator API should call backend curator endpoints')
assert.ok(router.includes("name: 'solonclaw.curator'"), 'router should expose curator page')
assert.ok(sidebar.includes("handleNav('solonclaw.curator')"), 'sidebar should link to curator page')
assert.ok(view.includes('fetchCuratorReports'), 'curator view should load report list')
assert.ok(view.includes('runCurator'), 'curator view should expose manual run action')
assert.ok(view.includes('fetchCuratorReport'), 'curator view should load selected report detail')
assert.ok(zh.includes("curator: '技能维护'"), 'Chinese sidebar locale should include curator label')
assert.ok(en.includes("curator: 'Curator'"), 'English sidebar locale should include curator label')
