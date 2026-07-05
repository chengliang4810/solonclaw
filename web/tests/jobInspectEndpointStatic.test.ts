import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const store = readFileSync(new URL('../src/stores/solonclaw/jobs.ts', import.meta.url), 'utf8')
const jobCard = readFileSync(
  new URL('../src/components/solonclaw/jobs/JobCard.vue', import.meta.url),
  'utf8',
)

assert.ok(store.includes('async function inspectJob('), 'jobs store should expose the inspect endpoint')
assert.ok(store.includes('return jobsApi.inspectJob(jobId, limit)'), 'store inspectJob should delegate to the API')
assert.ok(jobCard.includes('jobsStore.inspectJob(jobId.value, 20)'), 'job detail drawer should use the aggregate inspect endpoint')
assert.ok(!jobCard.includes('Promise.all(['), 'job detail drawer should not split inspect data into parallel job/run requests')
