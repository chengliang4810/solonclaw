import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const settingsFile = new URL('../src/components/solonclaw/settings/PlatformSettings.vue', import.meta.url)

const settings = readFileSync(settingsFile, 'utf8')

assert.ok(settings.includes('PlatformTextSettingRow'), 'Primary platform settings should import the shared text row')
assert.ok(settings.includes('PlatformSwitchSettingRow'), 'Primary platform settings should import the shared switch row')
assert.equal(
  (settings.match(/<PlatformSwitchSettingRow/g) || []).length,
  2,
  'Primary platform settings should render QR and mention switches through configured shared rows',
)
assert.equal(
  (settings.match(/<PlatformTextSettingRow/g) || []).length,
  1,
  'Feishu, DingTalk, and Weixin should render text fields through one configured shared row',
)
assert.ok(settings.includes('primarySettingConfigs'), 'Primary platform field metadata should live in one config map')
assert.ok(
  settings.includes('v-for="field in primarySettingConfigs[p.key]"'),
  'Primary platform fields should render from the current platform config',
)
assert.equal(
  (settings.match(/\{ type: 'text', field: '[^']+'/g) || []).length,
  12,
  'Primary domestic platform text fields should stay covered by the config',
)
assert.equal(
  (settings.match(/\{ type: 'switch', field: '[^']+'/g) || []).length,
  2,
  'Primary platform mention switches should stay covered by the config',
)
assert.ok(!settings.includes("p.key === 'feishu'"), 'Feishu primary settings should not keep a dedicated template block')
assert.ok(!settings.includes("p.key === 'dingtalk'"), 'DingTalk primary settings should not keep a dedicated template block')
assert.ok(!settings.includes("p.key === 'weixin'"), 'Weixin primary settings should not keep a dedicated template block')
assert.ok(!settings.includes('<SettingRow'), 'Primary platform settings should not keep duplicated SettingRow field shells')
assert.ok(!settings.includes('<Input'), 'Primary platform settings should not keep duplicated Input field shells')
assert.ok(!settings.includes('<Switch'), 'Primary platform settings should not keep duplicated Switch field shells')
