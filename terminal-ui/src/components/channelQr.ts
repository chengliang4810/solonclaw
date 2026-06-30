import type { ChannelOption, ChannelQrResponse } from '../gatewayTypes.js'

const QR_CHANNELS = ['weixin', 'feishu', 'dingtalk'] as const

export const channelSupportsQr = (channel: Pick<ChannelOption, 'key' | 'qr_supported'>): boolean =>
  channel.qr_supported === true && QR_CHANNELS.some(key => key === channel.key)

export const channelQrUrl = (qr: ChannelQrResponse | null): string => {
  if (!qr) {
    return ''
  }

  return qr.qrcode_url || qr.qr_url || qr.qrcode || ''
}

export const channelQrStatusActive = (status?: string): boolean =>
  status === 'initializing' || status === 'pending' || status === 'scanned'

export const channelQrMessage = (qr: ChannelQrResponse | null): string => {
  if (!qr) {
    return ''
  }

  return qr.error_message || qr.message || qr.error || ''
}
