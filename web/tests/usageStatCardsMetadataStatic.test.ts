import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const componentFile = new URL('../src/components/solonclaw/usage/StatCards.vue', import.meta.url)
const metadataFile = new URL('../src/shared/usageMetrics.ts', import.meta.url)
const component = readFileSync(componentFile, 'utf8')
const metadata = readFileSync(metadataFile, 'utf8')

assert.ok(
  component.includes('interface UsageStatCardItem'),
  'StatCards should describe each card with typed metric metadata',
)
assert.ok(
  component.includes("import { usageStatCardMetrics, type UsageStatCardMetricKey } from '@/shared/usageMetrics'"),
  'StatCards should reuse shared usage card metric definitions',
)
assert.ok(
  metadata.includes('export const usageStatCardMetrics'),
  'Shared usage metrics should expose card metadata',
)
assert.ok(
  component.includes('satisfies Record<UsageStatCardMetricKey, UsageStatCardDisplay>'),
  'StatCards should keep per-card values exhaustive against shared metric keys',
)
assert.ok(
  component.includes('usageStatCardMetrics.map(metric =>'),
  'StatCards should derive renderable card metadata from shared usage metric definitions',
)
assert.ok(
  component.includes('v-for="item in statCardItems"'),
  'StatCards should render cards from metric metadata instead of four duplicated card blocks',
)
assert.equal(
  (component.match(/<div class="stat-card"/g) || []).length,
  1,
  'StatCards should keep a single reusable stat-card shell',
)
