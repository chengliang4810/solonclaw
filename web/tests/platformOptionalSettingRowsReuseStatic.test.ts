import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'

const optionalSettingsFile = new URL('../src/components/solonclaw/settings/PlatformOptionalSettings.vue', import.meta.url)
const textRowFile = new URL('../src/components/solonclaw/settings/PlatformTextSettingRow.vue', import.meta.url)
const switchRowFile = new URL('../src/components/solonclaw/settings/PlatformSwitchSettingRow.vue', import.meta.url)
const platformSettingsFile = new URL('../src/components/solonclaw/settings/PlatformSettings.vue', import.meta.url)

assert.ok(existsSync(textRowFile), 'Optional channel text settings should use one shared row component')
assert.ok(existsSync(switchRowFile), 'Optional channel switch settings should use one shared row component')

const optionalSettings = readFileSync(optionalSettingsFile, 'utf8')
const textRow = readFileSync(textRowFile, 'utf8')
const switchRow = readFileSync(switchRowFile, 'utf8')
const platformSettings = readFileSync(platformSettingsFile, 'utf8')

assert.ok(optionalSettings.includes('PlatformTextSettingRow'), 'Optional settings should import the shared text row')
assert.ok(optionalSettings.includes('PlatformSwitchSettingRow'), 'Optional settings should import the shared switch row')
assert.equal(
  (optionalSettings.match(/<PlatformSwitchSettingRow/g) || []).length,
  1,
  'Optional platforms should render enabled state through one shared switch row',
)
assert.equal(
  (optionalSettings.match(/<PlatformTextSettingRow/g) || []).length,
  1,
  'Optional channel text fields should render through one shared text row loop',
)
assert.ok(optionalSettings.includes('textFieldConfigs'), 'Optional field metadata should live in one config map')
assert.ok(
  optionalSettings.includes('v-for="field in textFieldConfigs[platform]"'),
  'Optional field rows should be rendered from the current platform config',
)
assert.equal(
  (optionalSettings.match(/\{ field: '[^']+', source: 'credentials'/g) || []).length,
  4,
  'Optional platform credential fields should stay covered without duplicating QR primary credentials',
)
assert.equal(
  (platformSettings.match(/\{ type: 'text', field: '(?:app_id|client_secret)', source: 'credentials', label:/g) || []).length,
  2,
  'QQBot QR primary settings should retain its two credential fields',
)
assert.equal(
  (optionalSettings.match(/\{ field: '[^']+', source: 'channel'/g) || []).length,
  5,
  'Optional platform channel fields should stay covered by the config',
)
assert.ok(
  !optionalSettings.includes("platform === 'wecom'")
    && !optionalSettings.includes("platform === 'qqbot'")
    && !optionalSettings.includes("platform === 'yuanbao'"),
  'Optional settings should not keep separate per-platform template blocks',
)
assert.ok(!optionalSettings.includes('<SettingRow'), 'Optional settings should not keep duplicated SettingRow field shells')
assert.ok(!optionalSettings.includes('<Input'), 'Optional settings should not keep duplicated Input field shells')
assert.ok(!optionalSettings.includes('<Switch'), 'Optional settings should not keep duplicated Switch field shells')
assert.ok(textRow.includes('target instanceof HTMLInputElement'), 'Text row should extract real input values from change events')
assert.ok(textRow.includes("emit('change'"), 'Text row should emit normalized text changes')
assert.ok(switchRow.includes('value === true'), 'Switch row should normalize broad switch values to boolean')
