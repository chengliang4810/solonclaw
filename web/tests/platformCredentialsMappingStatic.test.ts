import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/config.ts', import.meta.url), 'utf8')

assert.ok(api.includes('CHANNEL_CREDENTIAL_FIELDS'), 'channel credentials should use one shared field mapping')

for (const platform of ['feishu', 'dingtalk', 'wecom', 'weixin', 'qqbot', 'yuanbao']) {
  assert.ok(!api.includes(`platform === '${platform}'`), `saveCredentials should not branch on ${platform}`)
}

for (const key of [
  'solonclaw.channels.feishu.appId',
  'solonclaw.channels.dingtalk.robotCode',
  'solonclaw.channels.wecom.botId',
  'solonclaw.channels.weixin.accountId',
  'solonclaw.channels.qqbot.clientSecret',
  'solonclaw.channels.yuanbao.appSecret',
]) {
  assert.ok(api.includes(key), `credential mapping should keep ${key}`)
}
