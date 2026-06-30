import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const componentFile = new URL('../src/components/solonclaw/usage/StatCards.vue', import.meta.url)
const component = readFileSync(componentFile, 'utf8')

assert.ok(
  component.includes('interface UsageStatCardItem'),
  'StatCards should describe each card with typed metric metadata',
)
assert.ok(
  component.includes('const statCardItems = computed<readonly UsageStatCardItem[]>(() =>'),
  'StatCards should derive renderable card metadata from usage store state',
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
