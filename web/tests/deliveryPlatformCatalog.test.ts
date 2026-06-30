import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  DOMESTIC_PLATFORM_KEYS,
  DOMESTIC_PLATFORM_LABEL_KEYS,
  isDomesticPlatformKey,
} from '../src/shared/domesticPlatforms.ts'

const jobForm = readFileSync(
  new URL('../src/components/solonclaw/jobs/JobFormModal.vue', import.meta.url),
  'utf8',
)
const platformDefinitions = readFileSync(
  new URL('../src/components/solonclaw/settings/platformDefinitions.ts', import.meta.url),
  'utf8',
)
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')

assert.deepEqual(
  DOMESTIC_PLATFORM_KEYS,
  ['feishu', 'dingtalk', 'wecom', 'weixin', 'qqbot', 'yuanbao'],
  'delivery platform catalog should keep the confirmed domestic platform order',
)
assert.equal(DOMESTIC_PLATFORM_LABEL_KEYS.feishu, 'jobs.platformFeishu')
assert.equal(isDomesticPlatformKey('yuanbao'), true)
assert.equal(isDomesticPlatformKey('telegram'), false)
assert.ok(jobForm.includes('DOMESTIC_PLATFORM_KEYS.map'), 'job form should render delivery options from shared platform keys')
assert.ok(!jobForm.includes("t('jobs.platformFeishu')"), 'job form should not inline platform option labels')
assert.ok(
  platformDefinitions.includes('PLATFORM_SETTINGS_KEYS = DOMESTIC_PLATFORM_KEYS'),
  'settings platform catalog should reuse the shared domestic platform keys',
)

for (const labelKey of Object.values(DOMESTIC_PLATFORM_LABEL_KEYS)) {
  const localeKey = labelKey.replace('jobs.', '')
  assert.ok(en.includes(`${localeKey}:`), `English jobs locale should include ${localeKey}`)
  assert.ok(zh.includes(`${localeKey}:`), `Chinese jobs locale should include ${localeKey}`)
}
