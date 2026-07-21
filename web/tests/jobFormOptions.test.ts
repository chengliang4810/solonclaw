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
const jobsApi = readFileSync(new URL('../src/api/solonclaw/jobs.ts', import.meta.url), 'utf8')

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
assert.ok(jobsApi.includes('fetchToolsets'), 'jobs API should expose the backend toolsets catalog')
assert.ok(jobsApi.includes("'/api/tools/toolsets'"), 'jobs API should fetch the backend toolsets endpoint')
assert.ok(jobForm.includes('toolsetOptions'), 'job form should render backend-backed toolset options')
assert.ok(jobForm.includes('mode="multiple"'), 'job form should use a multi-select for enabled toolsets')
assert.ok(jobForm.includes('formData.value.enabled_toolsets'), 'job form should preserve selected toolsets outside the catalog')
assert.ok(jobForm.includes('options.push({ label: value, value, disabled: false })'), 'job form should keep legacy selected toolsets selectable')

const toolsetSelect = jobForm.match(/<FormItem :label="t\('jobs\.enabledToolsets'\)">[\s\S]*?<Select([\s\S]*?)\/>/)
assert.ok(toolsetSelect, 'job form should render the enabled toolsets select')
assert.ok(toolsetSelect[1]?.includes(':virtual="false"'), 'enabled toolsets select should avoid hidden zero-size virtual ARIA options')

assert.ok(jobForm.includes('const hasCompleteModelBinding = Boolean(storedProvider && storedModel)'), 'legacy partial Cron model bindings should be cleared')
assert.ok(jobForm.includes("base_url: ''"), 'editing a Cron job should clear the legacy direct base URL')
assert.ok(jobForm.includes("['base_url', formData.value.base_url]"), 'Cron updates should submit null for the cleared legacy base URL')
assert.ok(
  jobForm.includes('hasText(formData.value.provider) !== hasText(formData.value.model)'),
  'Cron should reject partial Provider/model bindings',
)
assert.ok(jobForm.includes("t('models.unregisteredModel'"), 'legacy unregistered Cron models should remain visible as disabled options')
const cronModelSelect = jobForm.match(/<FormItem :label="t\('jobs\.model'\)">[\s\S]*?<Select([\s\S]*?)\/>/)
assert.ok(cronModelSelect, 'Cron form should render a model select')
assert.ok(!cronModelSelect[1]?.includes('allow-clear'), 'Cron model must be cleared together with its Provider')
