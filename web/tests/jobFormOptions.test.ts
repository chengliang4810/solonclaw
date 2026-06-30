import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  JOB_DELIVERY_MODE_OPTIONS,
  JOB_INTERVAL_UNIT_OPTIONS,
  JOB_SCHEDULE_KIND_OPTIONS,
  JOB_SCHEDULE_PRESET_OPTIONS,
  JOB_SKILL_EDIT_MODE_OPTIONS,
  JOB_STATE_OPTIONS,
  translateJobFormOptions,
} from '../src/shared/jobFormOptions.ts'

const jobForm = readFileSync(
  new URL('../src/components/solonclaw/jobs/JobFormModal.vue', import.meta.url),
  'utf8',
)

assert.deepEqual(JOB_SCHEDULE_KIND_OPTIONS.map(item => item.value), ['cron', 'interval', 'once'])
assert.deepEqual(JOB_INTERVAL_UNIT_OPTIONS.map(item => item.value), ['m', 'h', 'd'])
assert.deepEqual(JOB_STATE_OPTIONS.map(item => item.value), ['scheduled', 'paused', 'completed'])
assert.deepEqual(JOB_DELIVERY_MODE_OPTIONS.map(item => item.value), ['origin', 'local', 'platform', 'specific', 'multi'])
assert.deepEqual(JOB_SKILL_EDIT_MODE_OPTIONS.map(item => item.value), ['replace', 'merge', 'clear'])
assert.deepEqual(
  JOB_SCHEDULE_PRESET_OPTIONS.map(item => item.value),
  ['* * * * *', '*/5 * * * *', '0 * * * *', '0 0 * * *', '0 9 * * *', '0 9 * * 1', '0 9 1 * *'],
)

const translated = translateJobFormOptions(key => `label:${key}`, JOB_DELIVERY_MODE_OPTIONS)
assert.equal(translated[0]?.label, 'label:jobs.deliveryModeOrigin')
assert.equal(translated[0]?.value, 'origin')

for (const inlineLabel of [
  "t('jobs.scheduleKindCron')",
  "t('jobs.deliveryModeOrigin')",
  "t('jobs.skillEditReplace')",
  "t('jobs.presetEveryMinute')",
]) {
  assert.ok(!jobForm.includes(inlineLabel), `job form should not inline option label ${inlineLabel}`)
}

assert.ok(jobForm.includes('translateJobFormOptions(t, JOB_SCHEDULE_KIND_OPTIONS)'))
assert.ok(jobForm.includes('translateJobFormOptions(t, JOB_SCHEDULE_PRESET_OPTIONS)'))
