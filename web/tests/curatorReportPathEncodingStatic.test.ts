import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/curator.ts', import.meta.url), 'utf8')

assert.ok(
  api.includes('/api/curator/${encodeURIComponent(reportId)}'),
  'curator report detail must encode reportId before using it as a URL path segment',
)
assert.equal(
  api.includes('/api/curator/${reportId}'),
  false,
  'curator report detail must not interpolate raw reportId into the URL path',
)
