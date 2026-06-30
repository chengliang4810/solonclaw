import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'

const optionalSettingsFile = new URL('../src/components/solonclaw/settings/PlatformOptionalSettings.vue', import.meta.url)
const textRowFile = new URL('../src/components/solonclaw/settings/PlatformTextSettingRow.vue', import.meta.url)
const switchRowFile = new URL('../src/components/solonclaw/settings/PlatformSwitchSettingRow.vue', import.meta.url)

assert.ok(existsSync(textRowFile), 'Optional channel text settings should use one shared row component')
assert.ok(existsSync(switchRowFile), 'Optional channel switch settings should use one shared row component')

const optionalSettings = readFileSync(optionalSettingsFile, 'utf8')
const textRow = readFileSync(textRowFile, 'utf8')
const switchRow = readFileSync(switchRowFile, 'utf8')

assert.ok(optionalSettings.includes('PlatformTextSettingRow'), 'Optional settings should import the shared text row')
assert.ok(optionalSettings.includes('PlatformSwitchSettingRow'), 'Optional settings should import the shared switch row')
assert.equal(
  (optionalSettings.match(/<PlatformSwitchSettingRow/g) || []).length,
  3,
  'WeCom, QQBot, and Yuanbao should each render enabled state through the shared switch row',
)
assert.equal(
  (optionalSettings.match(/<PlatformTextSettingRow/g) || []).length,
  11,
  'Optional channel text fields should render through the shared text row',
)
assert.ok(!optionalSettings.includes('<SettingRow'), 'Optional settings should not keep duplicated SettingRow field shells')
assert.ok(!optionalSettings.includes('<Input'), 'Optional settings should not keep duplicated Input field shells')
assert.ok(!optionalSettings.includes('<Switch'), 'Optional settings should not keep duplicated Switch field shells')
assert.ok(textRow.includes('target instanceof HTMLInputElement'), 'Text row should extract real input values from change events')
assert.ok(textRow.includes("emit('change'"), 'Text row should emit normalized text changes')
assert.ok(switchRow.includes('value === true'), 'Switch row should normalize broad switch values to boolean')
