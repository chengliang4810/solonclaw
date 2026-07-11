import type { ChannelOption, ChannelQrResponse } from '../gatewayTypes.js'

const QR_CHANNELS = ['weixin', 'feishu', 'dingtalk'] as const
const ACTIVE_QR_STATUSES = ['wait', 'scaned', 'scaned_but_redirect'] as const

export type ChannelQrStatus = 'confirmed' | 'error' | 'expired' | 'scaned' | 'scaned_but_redirect' | 'wait'

export const channelSupportsQr = (channel: Pick<ChannelOption, 'key' | 'qr_supported'>): boolean =>
  channel.qr_supported === true && QR_CHANNELS.some(key => key === channel.key)

export const channelQrUrl = (qr: ChannelQrResponse | null): string => {
  if (!qr) {
    return ''
  }

  return qr.qrcode_url || qr.qr_image_url || qr.qrcode_img_content || qr.qr_url || qr.qr_code || qr.qrcode || ''
}

export const channelQrStatus = (qr: ChannelQrResponse | null): ChannelQrStatus => {
  if (qr?.status === 'confirmed') {
    return 'confirmed'
  }

  if (qr?.status === 'expired') {
    return 'expired'
  }

  if (qr?.status === 'error') {
    return 'error'
  }

  if (qr?.status === 'scaned' || qr?.status === 'scaned_but_redirect') {
    return qr.status
  }

  if (qr?.status === 'scanned') {
    return 'scaned'
  }

  if (qr?.status === 'failed') {
    return qr.error_code === 'qr_expired' || qr.error_code === 'qr_timeout' ? 'expired' : 'error'
  }

  return 'wait'
}

export const channelQrStatusActive = (status?: string): boolean =>
  ['initializing', 'pending', 'scanned', 'wait', 'scaned', 'scaned_but_redirect'].includes(status ?? '')

export const channelQrResponseStatusActive = (qr: ChannelQrResponse | null): boolean =>
  qr != null && ACTIVE_QR_STATUSES.some(status => status === channelQrStatus(qr))

export const channelQrMessage = (qr: ChannelQrResponse | null): string => {
  if (!qr) {
    return ''
  }

  return qr.error_message || qr.message || qr.error || ''
}

/** 仅在扫码确认后展示服务端返回的微信用户标识。 */
export const channelQrConfirmedUserId = (qr: ChannelQrResponse | null): string =>
  channelQrStatus(qr) === 'confirmed' ? qr?.user_id?.trim() || '' : ''
