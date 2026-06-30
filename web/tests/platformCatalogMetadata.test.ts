import assert from 'node:assert/strict'
import { normalizePlatformSettingsItems } from '../src/components/solonclaw/settings/platformDefinitions.ts'

const items = normalizePlatformSettingsItems([
  { code: 'yuanbao', displayName: '元宝助理', iconKey: 'yuanbao', order: 5 },
  { code: 'dingtalk', displayName: '钉钉工作台', iconKey: 'dingtalk', order: 9 },
  { code: 'feishu', displayName: '飞书入口', iconKey: 'feishu', order: 10 },
  { code: 'telegram', displayName: 'Telegram', iconKey: 'telegram', order: 1 },
  { code: 'qqbot', displayName: 'QQ 频道', iconKey: 'missing', order: 15 },
  { code: 'wecom', displayName: '企业微信', iconKey: 'wecom', order: 30 },
  { code: 'weixin', enabled: false, order: 20 },
])

assert.deepEqual(
  items.map(item => item.key),
  ['yuanbao', 'dingtalk', 'feishu', 'qqbot', 'wecom'],
  'platform catalog should sort confirmed domestic channels and ignore disabled or unsupported items',
)
assert.equal(items[0]?.name, '元宝助理', 'platform catalog should use backend display names')
const qqbot = items.find(item => item.key === 'qqbot')
assert.equal(qqbot?.name, 'QQ 频道', 'platform catalog should keep display names when icon key falls back')
assert.ok(qqbot?.icon.includes('<svg'), 'platform catalog should resolve icons from local SVG mapping')
