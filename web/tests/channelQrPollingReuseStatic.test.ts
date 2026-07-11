import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'

const sharedPollingFile = new URL('../src/shared/channelQrPolling.ts', import.meta.url)
const settingsFile = new URL('../src/components/solonclaw/settings/PlatformSettings.vue', import.meta.url)
const tuiRuntimeFile = new URL('../src/views/solonclaw/TuiRuntimeView.vue', import.meta.url)

assert.ok(existsSync(sharedPollingFile), 'Web QR setup polling should live in one shared helper')

const sharedPolling = readFileSync(sharedPollingFile, 'utf8')
const settings = readFileSync(settingsFile, 'utf8')
const tuiRuntime = readFileSync(tuiRuntimeFile, 'utf8')

for (const [name, source] of [['PlatformSettings', settings], ['TuiRuntimeView', tuiRuntime]] as const) {
  assert.ok(source.includes('useChannelQrPolling'), `${name} should use the shared QR polling helper`)
  assert.ok(!source.includes("import * as QRCode from 'qrcode'"), `${name} should not generate QR images directly`)
  assert.ok(!source.includes('function updateQrSource'), `${name} should not keep a local QR source updater`)
  assert.ok(!source.includes('failures: number'), `${name} should not keep local polling failure state shape`)
}

assert.ok(sharedPolling.includes("import * as QRCode from 'qrcode'"), 'Shared QR polling helper should own QR image generation')
assert.ok(sharedPolling.includes('normalizeChannelQrStatus'), 'Shared QR polling helper should normalize backend QR status payloads')
assert.ok(sharedPolling.includes('failures >= 3'), 'Shared QR polling helper should keep the retry ceiling in one place')
assert.ok(sharedPolling.includes('accountId: string'), 'Shared QR polling state should keep account id context')
assert.ok(sharedPolling.includes('baseUrl: string'), 'Shared QR polling state should keep base url context')
assert.ok(sharedPolling.includes('clientId: string'), 'Shared QR polling state should keep client id context')
assert.ok(sharedPolling.includes('appId: string'), 'Shared QR polling state should keep app id context')
assert.ok(sharedPolling.includes('openId: string'), 'Shared QR polling state should keep open id context')
assert.ok(sharedPolling.includes('userId: string'), 'Shared QR polling state should keep confirmed user id context')
assert.ok(sharedPolling.includes('state.accountId = view.account_id || \'\''), 'Shared QR polling should map account id from backend payload')
assert.ok(sharedPolling.includes('state.baseUrl = view.base_url || \'\''), 'Shared QR polling should map base url from backend payload')
assert.ok(sharedPolling.includes('state.clientId = view.client_id || \'\''), 'Shared QR polling should map client id from backend payload')
assert.ok(sharedPolling.includes('state.appId = view.app_id || \'\''), 'Shared QR polling should map app id from backend payload')
assert.ok(sharedPolling.includes('state.openId = view.open_id || \'\''), 'Shared QR polling should map open id from backend payload')
assert.ok(sharedPolling.includes('state.userId = view.user_id || \'\''), 'Shared QR polling should map user id from backend payload')
