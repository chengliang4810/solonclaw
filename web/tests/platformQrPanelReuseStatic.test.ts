import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'

const sharedPanelFile = new URL('../src/components/solonclaw/settings/ChannelQrPanel.vue', import.meta.url)
const settingsFile = new URL('../src/components/solonclaw/settings/PlatformSettings.vue', import.meta.url)
const zhFile = new URL('../src/i18n/locales/zh.ts', import.meta.url)
const enFile = new URL('../src/i18n/locales/en.ts', import.meta.url)

assert.ok(existsSync(sharedPanelFile), 'platform QR login should have one shared panel component')

const settings = readFileSync(settingsFile, 'utf8')
const sharedPanel = readFileSync(sharedPanelFile, 'utf8')
const zh = readFileSync(zhFile, 'utf8')
const en = readFileSync(enFile, 'utf8')

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
assert.ok(sharedPanel.includes('captionMessage(state)'), 'Shared QR panel should use one caption resolver for image and image-less terminal states')
assert.ok(sharedPanel.includes('state.status === \'error\' || state.status === \'expired\' ? (state.message || statusFallbackMessage(state.status)) :'), 'Shared QR panel should show terminal fallback text even when a QR image is still visible')
assert.ok(sharedPanel.includes('qrContextRows(state)'), 'Shared QR panel should render backend QR context rows')
assert.ok(sharedPanel.includes('state.accountId'), 'Shared QR panel should show account id context')
assert.ok(sharedPanel.includes('state.baseUrl'), 'Shared QR panel should show base url context')
assert.ok(sharedPanel.includes('state.clientId'), 'Shared QR panel should show client id context')
assert.ok(sharedPanel.includes('state.appId'), 'Shared QR panel should show app id context')
assert.ok(sharedPanel.includes('state.openId'), 'Shared QR panel should show open id context')
assert.ok(zh.includes("qrAccountId: '账号 ID'"), 'Chinese locale should label QR account id')
assert.ok(zh.includes("qrClientId: '客户端 ID'"), 'Chinese locale should label QR client id')
assert.ok(zh.includes("qrAppId: '应用 ID'"), 'Chinese locale should label QR app id')
assert.ok(zh.includes("qrOpenId: 'Open ID'"), 'Chinese locale should label QR open id')
assert.ok(zh.includes("qrBaseUrl: '服务地址'"), 'Chinese locale should label QR base url')
assert.ok(en.includes("qrAccountId: 'Account ID'"), 'English locale should label QR account id')
assert.ok(en.includes("qrClientId: 'Client ID'"), 'English locale should label QR client id')
assert.ok(en.includes("qrAppId: 'App ID'"), 'English locale should label QR app id')
assert.ok(en.includes("qrOpenId: 'Open ID'"), 'English locale should label QR open id')
assert.ok(en.includes("qrBaseUrl: 'Base URL'"), 'English locale should label QR base url')
