import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const componentFile = new URL('../src/components/solonclaw/usage/DailyTrend.vue', import.meta.url)
const metadataFile = new URL('../src/shared/usageMetrics.ts', import.meta.url)
const component = readFileSync(componentFile, 'utf8')
const metadata = readFileSync(metadataFile, 'utf8')

assert.ok(
  component.includes('interface UsageTrendColumn'),
  'DailyTrend should describe table and tooltip columns with typed metadata',
)
assert.ok(
  component.includes("import { dailyUsageTrendMetrics, type DailyUsageTrendMetricKey } from '@/shared/usageMetrics'"),
  'DailyTrend should reuse shared usage trend metric definitions',
)
assert.ok(
  metadata.includes('export const dailyUsageTrendMetrics'),
  'Shared usage metrics should expose daily trend column metadata',
)
assert.ok(
  component.includes('satisfies Record<DailyUsageTrendMetricKey, DailyUsageTrendFormatter>'),
  'DailyTrend should keep column formatters exhaustive against shared metric keys',
)
assert.ok(
  component.includes('dailyUsageTrendMetrics.map(metric =>'),
  'DailyTrend should expose a single column metadata source from shared metric definitions',
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
  0,
  'DailyTrend should not hardcode token labels after extracting shared metadata',
)
assert.equal(
  (metadata.match(/labelKey: 'usage\.tokens'/g) || []).length,
  1,
  'Shared usage metrics should define the token column label once',
)
