import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/runs.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/RunsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes('fetchRecoverableRuns'), 'runs API should expose the backend recoverable runs endpoint')
assert.ok(view.includes('fetchRecoverableRuns'), 'runs view should consume the recoverable runs endpoint')
assert.ok(view.includes('loadRecoverableRuns'), 'runs view should provide a recoverable runs loader')
assert.ok(view.includes("t('runs.recoverableRuns')"), 'runs view should render the recoverable runs section')
assert.ok(view.includes("t('runs.loadRecoverableRuns')"), 'runs view should expose a recoverable runs load action')
assert.ok(view.includes("t('runs.noRecoverableRuns')"), 'runs view should render an empty state for recoverable runs')
assert.ok(zh.includes("recoverableRuns: '可恢复运行'"), 'Chinese locale should include recoverable runs label')
assert.ok(en.includes("recoverableRuns: 'Recoverable runs'"), 'English locale should include recoverable runs label')
