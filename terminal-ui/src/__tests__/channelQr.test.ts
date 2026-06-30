import { PassThrough } from 'node:stream'

import { renderSync } from '@solonclaw/ink'
import React from 'react'
import { describe, expect, it } from 'vitest'

import { channelQrStatusActive, channelQrUrl, channelSupportsQr } from '../components/channelQr.js'
import { ChannelQrSetupView } from '../components/channelSetupViews.js'
import { stripAnsi } from '../lib/text.js'
import { DEFAULT_THEME } from '../theme.js'

describe('channel QR setup helpers', () => {
  it('marks only implemented domestic setup channels as QR-capable', () => {
    expect(channelSupportsQr({ key: 'weixin', label: 'Weixin', qr_supported: true })).toBe(true)
    expect(channelSupportsQr({ key: 'feishu', label: 'Feishu', qr_supported: true })).toBe(true)
    expect(channelSupportsQr({ key: 'dingtalk', label: 'DingTalk', qr_supported: true })).toBe(true)
    expect(channelSupportsQr({ key: 'wecom', label: 'WeCom' })).toBe(false)
  })

  it('uses platform-specific QR URL fields for display', () => {
    expect(channelQrUrl({ qrcode_url: 'https://weixin.example.test/qr.png' })).toBe('https://weixin.example.test/qr.png')
    expect(channelQrUrl({ qr_url: 'https://login.example.test/qr?code=1' })).toBe('https://login.example.test/qr?code=1')
    expect(channelQrUrl({ qrcode: 'raw-code' })).toBe('raw-code')
  })

  it('keeps polling only while QR setup can still change', () => {
    expect(channelQrStatusActive('initializing')).toBe(true)
    expect(channelQrStatusActive('pending')).toBe(true)
    expect(channelQrStatusActive('scanned')).toBe(true)
    expect(channelQrStatusActive('confirmed')).toBe(false)
    expect(channelQrStatusActive('failed')).toBe(false)
  })

  it('renders the QR setup surface without overflowing a narrow terminal', () => {
    const output = renderQrView(80)
    const lines = output.split('\n').map(line => stripAnsi(line).trimEnd())

    expect(output).toContain('Scan to bind Feishu')
    expect(output).toContain('https://accounts.feishu.test/qr')
    expect(output).toContain('polling every 1.5s')
    expect(lines.every(line => line.length <= 80)).toBe(true)
  })
})

const renderQrView = (columns: number): string => {
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
        ticket: 'ticket-1'
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
