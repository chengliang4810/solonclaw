import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/jobs.ts', import.meta.url), 'utf8')

assert.ok(
  api.includes('encodeJobPath'),
  'job detail endpoints should share one encoded path helper',
)
assert.equal(
  api.includes('/api/cron/jobs/${jobId}'),
  false,
  'job detail endpoints must not interpolate raw jobId into URL paths',
)
