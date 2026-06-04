export type ChannelQrPlatform = 'weixin' | 'feishu' | 'dingtalk'

export type ChannelQrStatus =
  | 'wait'
  | 'scaned'
  | 'scaned_but_redirect'
  | 'expired'
  | 'confirmed'
  | 'error'

export interface ChannelQrStatusView {
  status: ChannelQrStatus
  qrcode: string
  qrcode_url: string
  message: string
  error_message: string
  account_id?: string
  base_url?: string
  client_id?: string
  app_id?: string
  open_id?: string
  domain?: string
}

export function normalizeChannelQrStatus(res: Record<string, any>): ChannelQrStatusView {
  const statusMap: Record<string, ChannelQrStatus> = {
    initializing: 'wait',
    pending: 'wait',
    scanned: 'scaned',
    confirmed: 'confirmed',
    failed: res.error_code === 'qr_expired' || res.error_code === 'qr_timeout' ? 'expired' : 'error',
  }
  return {
    status: statusMap[res.status] || 'wait',
    qrcode: res.ticket || '',
    qrcode_url: res.qr_image_url || res.qr_code || res.qr_url || '',
    message: res.message || '',
    error_message: res.error_message || '',
    account_id: res.account_id,
    base_url: res.base_url,
    client_id: res.client_id,
    app_id: res.app_id,
    open_id: res.open_id,
    domain: res.domain,
  }
}
