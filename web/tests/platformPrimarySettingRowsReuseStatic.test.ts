import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const settingsFile = new URL('../src/components/solonclaw/settings/PlatformSettings.vue', import.meta.url)

const settings = readFileSync(settingsFile, 'utf8')

assert.ok(settings.includes('PlatformTextSettingRow'), 'Primary platform settings should import the shared text row')
assert.ok(settings.includes('PlatformSwitchSettingRow'), 'Primary platform settings should import the shared switch row')
assert.equal(
  (settings.match(/<PlatformSwitchSettingRow/g) || []).length,
  3,
  'Primary platform settings should render repeated channel switches through one configured row and mention switches through shared rows',
)
assert.equal(
  (settings.match(/<PlatformTextSettingRow/g) || []).length,
  9,
  'Feishu, DingTalk, and Weixin should render text fields through the shared text row',
)
assert.ok(!settings.includes('<SettingRow'), 'Primary platform settings should not keep duplicated SettingRow field shells')
assert.ok(!settings.includes('<Input'), 'Primary platform settings should not keep duplicated Input field shells')
assert.ok(!settings.includes('<Switch'), 'Primary platform settings should not keep duplicated Switch field shells')
