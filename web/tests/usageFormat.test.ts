import assert from 'node:assert/strict'
import {
  formatUsageDateLabel,
  formatUsageCost,
  formatUsageTokens,
  latestUsageRows,
  maxUsageValue,
  usageCostFormatPresets,
  usageBarPercent,
} from '../src/shared/usageFormat.ts'

assert.equal(formatUsageTokens(999), '999')
assert.equal(formatUsageTokens(1000), '1.0K')
assert.equal(formatUsageTokens(12500), '12.5K')
assert.equal(formatUsageTokens(1000000), '1.0M')
assert.equal(formatUsageTokens(2350000), '2.4M')

assert.equal(formatUsageCost(1234567, ''), 'USD 1.2346')
assert.equal(formatUsageCost(12345678, 'CNY', usageCostFormatPresets.summary), 'CNY 12.35')
assert.equal(formatUsageCost(12345678, 'CNY', usageCostFormatPresets.detail), 'CNY 12.3457')
assert.equal(formatUsageCost(0, 'USD', usageCostFormatPresets.detail), '--')

assert.equal(formatUsageDateLabel('2026-06-30'), '06-30')
assert.equal(formatUsageDateLabel('2026'), '2026')
assert.equal(maxUsageValue([]), 1)
assert.equal(maxUsageValue([3, 9, 1]), 9)
assert.equal(usageBarPercent(5, 10), '50%')
assert.equal(usageBarPercent(0, 0), '0%')
assert.deepEqual(latestUsageRows(['day-1', 'day-2', 'day-3'], 2), ['day-3', 'day-2'])
