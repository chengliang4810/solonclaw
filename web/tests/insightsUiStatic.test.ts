import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/insights.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes("'/api/insights/overview'"), 'insights overview API should be wrapped')
assert.ok(api.includes("'/api/insights/skills'"), 'skill insights API should be wrapped')
assert.ok(view.includes('fetchInsightsOverview'), 'diagnostics view should load runtime insights')
assert.ok(view.includes('fetchSkillInsights'), 'diagnostics view should load skill insights')
assert.ok(view.includes("t('diagnostics.insights')"), 'diagnostics view should render an insights panel')
assert.ok(zh.includes("insights: '运行洞察'"), 'Chinese locale should name the insights panel')
assert.ok(en.includes("insights: 'Runtime insights'"), 'English locale should name the insights panel')
