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
  user_id: undefined,
  domain: undefined,
  bot_id: undefined,
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

assert.deepEqual(normalizeChannelQrStatus({
  ticket: 'wx-ticket',
  status: 'pending',
  qrcode: 'wx-qrcode',
  qrcode_url: 'https://liteapp.weixin.qq.com/q/7GiQu1?qrcode=wx-qrcode&bot_type=3',
  message: '请使用微信扫码',
  account_id: 'wx-account',
  base_url: 'https://ilink.example',
}), {
  status: 'wait',
  qrcode: 'wx-ticket',
  qrcode_url: 'https://liteapp.weixin.qq.com/q/7GiQu1?qrcode=wx-qrcode&bot_type=3',
  message: '请使用微信扫码',
  error_message: '',
  account_id: 'wx-account',
  base_url: 'https://ilink.example',
  client_id: undefined,
  app_id: undefined,
  open_id: undefined,
  user_id: undefined,
  domain: undefined,
  bot_id: undefined,
})

assert.equal(normalizeChannelQrStatus({
  status: 'scaned',
}).status, 'scaned')

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

assert.equal(normalizeChannelQrStatus({
  status: 'confirmed',
  user_id: 'wx-user',
}).user_id, 'wx-user')

assert.equal(normalizeChannelQrStatus({
  status: 'confirmed',
  bot_id: 'wecom-bot',
}).bot_id, 'wecom-bot')
