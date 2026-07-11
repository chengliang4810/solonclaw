import { request } from '../client'

export interface PairingUser {
  user_id: string
  user_name?: string
  chat_id?: string
  created_at?: number
  expires_at?: number
  approved_at?: number
}

export interface PairingPlatform {
  platform: string
  admin?: PairingUser | null
  pending: PairingUser[]
  approved: PairingUser[]
}

export async function fetchPairing(): Promise<PairingPlatform[]> {
  const data = await request<{ platforms: PairingPlatform[] }>('/api/gateway/pairing')
  return data.platforms || []
}

export function approvePairing(platform: string, code: string) {
  return request<PairingUser>('/api/gateway/pairing/approve', {
    method: 'POST',
    body: JSON.stringify({ platform, code }),
  })
}

export function revokePairing(platform: string, userId: string) {
  return request('/api/gateway/pairing/revoke', {
    method: 'POST',
    body: JSON.stringify({ platform, user_id: userId }),
  })
}

export function setPairingAdmin(platform: string, userId: string, userName = '', chatId = '') {
  return request('/api/gateway/pairing/admin', {
    method: 'PUT',
    body: JSON.stringify({ platform, user_id: userId, user_name: userName, chat_id: chatId }),
  })
}

export function clearPairingAdmin(platform: string) {
  return request('/api/gateway/pairing/admin', {
    method: 'DELETE',
    body: JSON.stringify({ platform }),
  })
}
