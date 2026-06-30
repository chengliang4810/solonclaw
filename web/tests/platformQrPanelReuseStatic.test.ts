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
  1,
  'Feishu, DingTalk, and Weixin should share one configured QR panel call site',
)
assert.ok(
  !settings.includes('class="channel-qr-section"'),
  'PlatformSettings should not keep duplicated channel QR section markup',
)
assert.ok(settings.includes('isQrPanelPlatform(p.key)'), 'PlatformSettings should gate QR panels through a platform predicate')
assert.ok(settings.includes(':domain="qrPanelDomain(p.key)"'), 'Feishu QR should keep the confirmed domain display through config')
assert.ok(settings.includes(':show-empty-status="shouldShowQrEmptyStatus(p.key)"'), 'Weixin QR should keep empty-image hint and error states through config')
assert.ok(settings.includes('@start="startQrLogin(p.key)"'), 'Configured QR panel should start the selected platform login')
assert.ok(sharedPanel.includes('state.status === \'loading\''), 'Shared QR panel should keep the loading state')
assert.ok(sharedPanel.includes('canStartQrLogin(state.status)'), 'Shared QR panel should keep the restart button state guard')
assert.ok(sharedPanel.includes('target="_blank"'), 'Shared QR panel should keep external QR link target')
assert.ok(sharedPanel.includes('rel="noopener noreferrer"'), 'Shared QR panel should keep external QR link safety attributes')
assert.ok(sharedPanel.includes('state.status === \'confirmed\' && domain'), 'Shared QR panel should keep the confirmed domain branch')
assert.ok(sharedPanel.includes("return showEmptyStatus && (state.status === 'waiting' || state.status === 'scaned')"), 'Shared QR panel should keep Weixin empty-image status branch')
assert.ok(sharedPanel.includes('shouldShowStandaloneStatus(state, showEmptyStatus)'), 'Shared QR panel should show image-less error and expired states across platforms')
assert.ok(sharedPanel.includes('statusFallbackMessage(state.status)'), 'Shared QR panel should provide fallback text for image-less terminal states')
