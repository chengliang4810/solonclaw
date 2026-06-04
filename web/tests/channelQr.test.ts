import assert from 'node:assert/strict'
import { normalizeChannelQrStatus } from '../src/shared/channelQr.ts'

assert.deepEqual(normalizeChannelQrStatus({
  ticket: 'ticket-1',
  status: 'pending',
  qr_url: 'https://login.example/qr',
  message: 'waiting',
}), {
  status: 'wait',
  qrcode: 'ticket-1',
  qrcode_url: 'https://login.example/qr',
  message: 'waiting',
  error_message: '',
  account_id: undefined,
  base_url: undefined,
  client_id: undefined,
  app_id: undefined,
  open_id: undefined,
  domain: undefined,
})

assert.equal(normalizeChannelQrStatus({
  status: 'initializing',
  ticket: 'ticket-2',
}).status, 'wait')

assert.equal(normalizeChannelQrStatus({
  status: 'failed',
  error_code: 'qr_timeout',
}).status, 'expired')

assert.equal(normalizeChannelQrStatus({
  status: 'failed',
  error_code: 'qr_failed',
}).status, 'error')

assert.equal(normalizeChannelQrStatus({
  status: 'confirmed',
  client_id: 'ding-client',
}).client_id, 'ding-client')

assert.equal(normalizeChannelQrStatus({
  status: 'confirmed',
  app_id: 'feishu-app',
  open_id: 'ou-owner',
  domain: 'lark',
}).open_id, 'ou-owner')

assert.equal(normalizeChannelQrStatus({
  status: 'confirmed',
  app_id: 'feishu-app',
  open_id: 'ou-owner',
  domain: 'lark',
}).domain, 'lark')
