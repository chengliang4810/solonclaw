import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const componentFile = new URL('../src/components/solonclaw/usage/DailyTrend.vue', import.meta.url)
const component = readFileSync(componentFile, 'utf8')

assert.ok(
  component.includes('interface UsageTrendColumn'),
  'DailyTrend should describe table and tooltip columns with typed metadata',
)
assert.ok(
  component.includes('const usageTrendColumns = computed<readonly UsageTrendColumn[]>(() =>'),
  'DailyTrend should expose a single column metadata source',
)
assert.ok(
  component.includes('v-for="column in usageTrendColumns"'),
  'DailyTrend should render headers and tooltip rows from the same column metadata',
)
assert.ok(
  component.includes('column.format(d)'),
  'DailyTrend should format row cells through column metadata',
)
assert.ok(
  component.includes('latestUsageRows(usageStore.dailyUsage)'),
  'DailyTrend should keep table row windowing in the shared usage format helper',
)
assert.equal(
  (component.match(/t\('usage\.tokens'\)/g) || []).length,
  1,
  'DailyTrend should not duplicate token column labels across tooltip and table markup',
)
