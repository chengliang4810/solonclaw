import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/curator.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/CuratorView.vue', import.meta.url), 'utf8')
const service = readFileSync(
  new URL('../../src/main/java/com/jimuqu/solon/claw/web/DashboardCuratorService.java', import.meta.url),
  'utf8',
)

assert.ok(
  api.includes('report?: unknown'),
  'curator detail type must expose the report field returned by the dashboard API',
)
assert.ok(
  view.includes('selected.value.report ?? selected.value.report_json ?? selected.value'),
  'curator detail JSON panel must prefer backend report content before falling back to the wrapper object',
)
assert.ok(
  /map\.put\(\s*"report"\s*,/.test(service),
  'dashboard curator detail API must keep returning parsed detail content under report',
)
