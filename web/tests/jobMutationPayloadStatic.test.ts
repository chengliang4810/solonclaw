import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/jobs.ts', import.meta.url), 'utf8')

assert.ok(
  api.includes('function buildJobMutationPayload('),
  'jobs API should share one payload builder for create and update requests',
)
assert.equal(
  (api.match(/body: JSON\.stringify\(buildJobMutationPayload\(/g) || []).length,
  2,
  'createJob and updateJob should both serialize the shared mutation payload builder',
)
