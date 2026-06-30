import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'

const sharedPanelFile = new URL('../src/components/solonclaw/settings/ChannelQrPanel.vue', import.meta.url)
const settingsFile = new URL('../src/components/solonclaw/settings/PlatformSettings.vue', import.meta.url)

assert.ok(existsSync(sharedPanelFile), 'platform QR login should have one shared panel component')

const settings = readFileSync(settingsFile, 'utf8')
const sharedPanel = readFileSync(sharedPanelFile, 'utf8')

assert.ok(settings.includes('ChannelQrPanel'), 'PlatformSettings should render QR login through the shared panel')
assert.equal(
  (settings.match(/<ChannelQrPanel/g) || []).length,
  3,
  'Feishu, DingTalk, and Weixin should each use the shared QR panel once',
)
assert.ok(
  !settings.includes('class="channel-qr-section"'),
  'PlatformSettings should not keep duplicated channel QR section markup',
)
assert.ok(settings.includes(':domain="getCreds(\'feishu\').domain"'), 'Feishu QR should keep the confirmed domain display')
assert.ok(settings.includes('show-empty-status'), 'Weixin QR should keep empty-image hint and error states')
assert.ok(sharedPanel.includes('state.status === \'loading\''), 'Shared QR panel should keep the loading state')
assert.ok(sharedPanel.includes('canStartQrLogin(state.status)'), 'Shared QR panel should keep the restart button state guard')
assert.ok(sharedPanel.includes('target="_blank"'), 'Shared QR panel should keep external QR link target')
assert.ok(sharedPanel.includes('rel="noopener noreferrer"'), 'Shared QR panel should keep external QR link safety attributes')
assert.ok(sharedPanel.includes('state.status === \'confirmed\' && domain'), 'Shared QR panel should keep the confirmed domain branch')
assert.ok(sharedPanel.includes('showEmptyStatus && !state.imageUrl'), 'Shared QR panel should keep Weixin empty-image status branch')
