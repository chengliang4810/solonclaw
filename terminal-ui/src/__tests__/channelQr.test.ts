import { PassThrough } from 'node:stream'

import { renderSync } from '@solonclaw/ink'
import React from 'react'
import { describe, expect, it } from 'vitest'

import {
  channelQrConfirmedUserId,
  channelQrResponseStatusActive,
  channelQrStatus,
  channelQrStatusActive,
  channelQrUrl,
  channelSupportsQr
} from '../components/channelQr.js'
import {
  ChannelQrSetupView,
  channelSetupFieldValueLabel,
  channelSetupListRowLabel
} from '../components/channelSetupViews.js'
import { stripAnsi } from '../lib/text.js'
import { DEFAULT_THEME } from '../theme.js'

describe('channel QR setup helpers', () => {
  it('marks only implemented domestic setup channels as QR-capable', () => {
    expect(channelSupportsQr({ key: 'weixin', label: 'Weixin', qr_supported: true })).toBe(true)
    expect(channelSupportsQr({ key: 'feishu', label: 'Feishu', qr_supported: true })).toBe(true)
    expect(channelSupportsQr({ key: 'dingtalk', label: 'DingTalk', qr_supported: true })).toBe(true)
    expect(channelSupportsQr({ key: 'wecom', label: 'WeCom', qr_supported: true })).toBe(true)
    expect(channelSupportsQr({ key: 'qqbot', label: 'QQBot', qr_supported: true })).toBe(true)
  })

  it('uses platform-specific QR URL fields for display', () => {
    expect(channelQrUrl({ qrcode_url: 'https://weixin.example.test/qr.png' })).toBe('https://weixin.example.test/qr.png')
    expect(channelQrUrl({ qr_image_url: 'https://weixin.example.test/qr-image.png' })).toBe('https://weixin.example.test/qr-image.png')
    expect(channelQrUrl({ qrcode_img_content: 'data:image/png;base64,abc' })).toBe('data:image/png;base64,abc')
    expect(channelQrUrl({ qr_url: 'https://login.example.test/qr?code=1' })).toBe('https://login.example.test/qr?code=1')
    expect(channelQrUrl({ qr_code: 'qr-code-value' })).toBe('qr-code-value')
    expect(channelQrUrl({ qrcode: 'raw-code' })).toBe('raw-code')
  })

  it('normalizes backend QR statuses to the Web dashboard contract', () => {
    expect(channelQrStatus({ status: 'initializing' })).toBe('wait')
    expect(channelQrStatus({ status: 'pending' })).toBe('wait')
    expect(channelQrStatus({ status: 'scanned' })).toBe('scaned')
    expect(channelQrStatus({ status: 'failed', error_code: 'qr_timeout' })).toBe('expired')
    expect(channelQrStatus({ status: 'failed', error_code: 'qr_failed' })).toBe('error')
    expect(channelQrResponseStatusActive({ status: 'pending' })).toBe(true)
    expect(channelQrResponseStatusActive(null)).toBe(false)
    expect(channelQrResponseStatusActive({ status: 'failed', error_code: 'qr_timeout' })).toBe(false)
  })

  it('exposes the confirmed Weixin user ID only after QR confirmation', () => {
    expect(channelQrConfirmedUserId({ status: 'pending', user_id: 'wx-user' })).toBe('')
    expect(channelQrConfirmedUserId({ status: 'confirmed', user_id: ' wx-user ' })).toBe('wx-user')
    expect(channelQrConfirmedUserId({ status: 'confirmed', user_openid: ' qq-owner ' })).toBe('qq-owner')
  })

  it('labels channel setup rows with configured and QR capability state', () => {
    expect(channelSetupListRowLabel({ configured: true, key: 'feishu', label: 'Feishu', qr_supported: true })).toBe(
      'Feishu · configured · QR'
    )
    expect(channelSetupListRowLabel({ key: 'wecom', label: 'WeCom' })).toBe('WeCom · not configured')
  })

  it('masks secret setup field values without hiding empty values', () => {
    expect(channelSetupFieldValueLabel({ key: 'secret', secret: true }, 'abc123')).toBe('••••••')
    expect(channelSetupFieldValueLabel({ key: 'secret', secret: true }, '')).toBe('')
    expect(channelSetupFieldValueLabel({ key: 'name' }, 'plain')).toBe('plain')
    expect(channelSetupFieldValueLabel({ key: 'secret', secret: true }, 'x'.repeat(50))).toHaveLength(40)
  })

  it('keeps polling only while QR setup can still change', () => {
    expect(channelQrStatusActive('initializing')).toBe(true)
    expect(channelQrStatusActive('pending')).toBe(true)
    expect(channelQrStatusActive('scanned')).toBe(true)
    expect(channelQrStatusActive('wait')).toBe(true)
    expect(channelQrStatusActive('scaned')).toBe(true)
    expect(channelQrStatusActive('scaned_but_redirect')).toBe(true)
    expect(channelQrStatusActive('confirmed')).toBe(false)
    expect(channelQrStatusActive('failed')).toBe(false)
    expect(channelQrStatusActive('expired')).toBe(false)
    expect(channelQrStatusActive('error')).toBe(false)
  })

  it('renders the QR setup surface without overflowing a narrow terminal', () => {
    const output = renderQrView(80)
    const lines = output.split('\n').map(line => stripAnsi(line).trimEnd())

    expect(output).toContain('Scan to bind Feishu')
    expect(output).toContain('https://accounts.feishu.test/qr')
    expect(output).toContain('polling every 1.5s')
    expect(lines.every(line => line.length <= 80)).toBe(true)
  })

  it('renders the confirmed Weixin user ID', () => {
    const output = renderQrView(80, { status: 'confirmed', user_id: 'wx-user' })

    expect(output).toContain('user ID: wx-user')
  })
})

const renderQrView = (columns: number, qrOverrides: Record<string, string> = {}): string => {
  const stdout = new PassThrough()
  const stdin = new PassThrough()
  const stderr = new PassThrough()
  let output = ''

  Object.assign(stdout, { columns, isTTY: false, rows: 24 })
  Object.assign(stdin, { isTTY: false })
  Object.assign(stderr, { isTTY: false })
  stdout.on('data', chunk => {
    output += chunk.toString()
  })

  const instance = renderSync(
    React.createElement(ChannelQrSetupView, {
      channel: { key: 'feishu', label: 'Feishu', qr_supported: true },
      err: '',
      qr: {
        message: '请使用飞书扫码授权',
        ok: true,
        qr_url: 'https://accounts.feishu.test/qr?code=1234567890&from=solonclaw&tp=solonclaw',
        status: 'pending',
        ticket: 'ticket-1',
        ...qrOverrides
      },
      qrLoading: false,
      t: DEFAULT_THEME,
      width: columns - 6
    }),
    {
      patchConsole: false,
      stderr: stderr as NodeJS.WriteStream,
      stdin: stdin as NodeJS.ReadStream,
      stdout: stdout as NodeJS.WriteStream
    }
  )

  instance.unmount()
  instance.cleanup()

  return output
}
