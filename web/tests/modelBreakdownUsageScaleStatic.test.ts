import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const componentFile = new URL('../src/components/solonclaw/usage/ModelBreakdown.vue', import.meta.url)
const component = readFileSync(componentFile, 'utf8')

assert.ok(component.includes("import { computed } from 'vue'"), 'ModelBreakdown should use a computed maximum token scale')
assert.ok(
  component.includes('const maxTokens = computed(() =>'),
  'ModelBreakdown should expose maxTokens as a computed value',
)
assert.ok(
  component.includes('maxUsageValue(usageStore.modelUsage.map(m => m.totalTokens))'),
  'ModelBreakdown should normalize model bars against the real maximum totalTokens value',
)
assert.ok(
  !component.includes('usageStore.modelUsage[0].totalTokens'),
  'ModelBreakdown should not assume the first model usage row has the largest totalTokens value',
)
assert.ok(
  component.includes('usageBarPercent(m.totalTokens, maxTokens)'),
  'ModelBreakdown should render each bar width through the shared usage bar helper',
)
