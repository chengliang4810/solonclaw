import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const helper = readFileSync(new URL('../src/shared/usage-format.ts', import.meta.url), 'utf8')
const statCards = readFileSync(new URL('../src/components/solonclaw/usage/StatCards.vue', import.meta.url), 'utf8')
const dailyTrend = readFileSync(new URL('../src/components/solonclaw/usage/DailyTrend.vue', import.meta.url), 'utf8')
const modelBreakdown = readFileSync(new URL('../src/components/solonclaw/usage/ModelBreakdown.vue', import.meta.url), 'utf8')

assert.ok(helper.includes('export function formatTokens'), 'shared usage token formatter should exist')
assert.ok(helper.includes("'K'"), 'usage token formatter should keep uppercase K')
for (const component of [statCards, dailyTrend, modelBreakdown]) {
  assert.ok(component.includes('@/shared/usage-format'), 'usage component should import shared formatter')
  assert.ok(!component.includes('function formatTokens'), 'usage component should not keep local formatter')
}
