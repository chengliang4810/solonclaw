import { request } from '../client'

export interface PairingUser {
  user_id: string
  user_name?: string
  chat_id?: string
  created_at?: number
  expires_at?: number
  approved_at?: number
}

/** 当前 Profile 在渠道内用于主动通知的默认私聊。 */
export interface PairingHomeChannel {
  chat_id: string
  chat_name?: string
  thread_id?: string
  updated_at?: number
  primary?: boolean
}

export interface PairingPlatform {
  platform: string
  admin?: PairingUser | null
  home_channel?: PairingHomeChannel | null
  pending?: PairingUser[]
  approved?: PairingUser[]
}

export interface PairingOwnerClaim {
  platform: string
  admin: PairingUser
  ok: boolean
  welcome_delivery?: {
    status?: 'pending' | 'sent' | 'failed'
    error?: string
  }
}

export interface PairingPrimaryUpdate {
  ok: boolean
  platform: string
  home_channel: PairingHomeChannel
}

export type PairingChannelState = 'connected' | 'disconnected' | 'disabled' | 'fatal'

export async function fetchPairing(): Promise<PairingPlatform[]> {
  const data = await request<{ platforms: PairingPlatform[] }>('/api/gateway/pairing')
  return data.platforms || []
}

/** 读取当前 Profile 下各渠道的真实网关连接状态。 */
export async function fetchPairingChannelStates(): Promise<Record<string, PairingChannelState>> {
  const data = await request<{ gateway_platforms?: Record<string, { state?: PairingChannelState }> }>('/api/status')
  return Object.fromEntries(
    Object.entries(data.gateway_platforms || {}).map(([platform, value]) => [platform, value.state || 'disconnected']),
  )
}

/** 使用本人首次私聊产生的配对码绑定当前 Profile 的渠道主人。 */
export function claimPairingOwner(platform: string, code: string) {
  return request<PairingOwnerClaim>('/api/gateway/pairing/claim-owner', {
    method: 'POST',
    body: JSON.stringify({ platform, code }),
  })
}

/** 向服务端保存的主人私聊重发欢迎语，不传递用户或会话标识。 */
export function retryPairingWelcome(platform: string) {
  return request<PairingOwnerClaim>('/api/gateway/pairing/welcome/retry', {
    method: 'POST',
    body: JSON.stringify({ platform }),
  })
}

/** 将当前已绑定平台设为该 Profile 的唯一主要通知渠道。 */
export function setPrimaryNotificationChannel(platform: string) {
  return request<PairingPrimaryUpdate>('/api/gateway/pairing/primary', {
    method: 'POST',
    body: JSON.stringify({ platform }),
  })
}
