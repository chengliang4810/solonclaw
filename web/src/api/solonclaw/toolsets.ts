import { request } from '../client'

export interface ToolsetInfo {
  name: string
  label?: string
  description?: string
  enabled?: boolean
  configured?: boolean
  tools?: string[]
}

export interface PlatformToolsets {
  platform: string
  enabledToolsets: string[]
  disabledToolsets: string[]
  approvalRequired: boolean
}

export async function fetchToolsets(): Promise<ToolsetInfo[]> {
  return request<ToolsetInfo[]>('/api/tools/toolsets')
}

export async function fetchPlatformToolsets(): Promise<Record<string, PlatformToolsets>> {
  const res = await request<{ platforms: Record<string, PlatformToolsets> }>('/api/tools/platform-toolsets')
  return res.platforms || {}
}

export async function updatePlatformToolsets(platform: string, data: {
  enabledToolsets: string[]
  disabledToolsets: string[]
  approvalRequired: boolean
}): Promise<PlatformToolsets> {
  return request<PlatformToolsets>(`/api/tools/platform-toolsets/${encodeURIComponent(platform)}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  })
}
