import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const apiFile = new URL('../src/api/solonclaw/usage.ts', import.meta.url)
const source = readFileSync(apiFile, 'utf8')

assert.ok(
  source.includes('interface UsageBreakdownItem'),
  'Usage API should keep daily/model shared token fields in one local interface',
)
assert.ok(
  source.includes('export interface DailyUsageItem extends UsageBreakdownItem'),
  'Daily usage rows should extend shared usage breakdown fields',
)
assert.ok(
  source.includes('export interface ModelUsageItem extends UsageBreakdownItem'),
  'Model usage rows should extend shared usage breakdown fields',
)
