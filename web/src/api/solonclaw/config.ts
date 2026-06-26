import { request } from '../client'
import type { ChannelQrPlatform, ChannelQrStatusView } from '@/shared/channelQr'
import { normalizeChannelQrStatus } from '@/shared/channelQr'

export interface DisplayConfig {
  compact?: boolean
  personality?: string
  resume_display?: string
  busy_input_mode?: string
  bell_on_complete?: boolean
  show_reasoning?: boolean
  streaming?: boolean
  inline_diffs?: boolean
  skin?: string
}

export interface AgentConfig {
  max_turns?: number
  gateway_timeout?: number
  restart_drain_timeout?: number
  service_tier?: string
  tool_use_enforcement?: string
}

export interface MemoryConfig {
  memory_enabled?: boolean
  user_profile_enabled?: boolean
  memory_char_limit?: number
  user_char_limit?: number
}

export interface SessionResetConfig {
  mode?: string
  idle_minutes?: number
  at_hour?: number
}

export interface PrivacyConfig {
  redact_pii?: boolean
}

export interface AppConfig {
  display?: DisplayConfig
  agent?: AgentConfig
  memory?: MemoryConfig
  session_reset?: SessionResetConfig
  privacy?: PrivacyConfig
  wecom?: Record<string, any>
  feishu?: Record<string, any>
  dingtalk?: Record<string, any>
  weixin?: Record<string, any>
  qqbot?: Record<string, any>
  yuanbao?: Record<string, any>
  platforms?: Record<string, any>
  [key: string]: any
}

interface WorkspaceConfigInfo {
  is_set: boolean
  redacted_value?: string | null
}

function configPreview(config: Record<string, WorkspaceConfigInfo>, key: string): string {
  const item = config[key]
  if (!item || !item.is_set) return ''
  return item.redacted_value || '已设置'
}

function configBoolean(value: unknown): boolean {
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value !== 0
  if (typeof value !== 'string') return false
  const normalized = value.trim().toLowerCase()
  return normalized === 'true' || normalized === '1' || normalized === 'yes' || normalized === 'on'
}

type CredentialField = {
  path: string[]
  key: string
}

const CHANNEL_CREDENTIAL_FIELDS: Record<string, CredentialField[]> = {
  feishu: [
    { path: ['enabled'], key: 'solonclaw.channels.feishu.enabled' },
    { path: ['extra', 'app_id'], key: 'solonclaw.channels.feishu.appId' },
    { path: ['extra', 'app_secret'], key: 'solonclaw.channels.feishu.appSecret' },
  ],
  dingtalk: [
    { path: ['enabled'], key: 'solonclaw.channels.dingtalk.enabled' },
    { path: ['extra', 'client_id'], key: 'solonclaw.channels.dingtalk.clientId' },
    { path: ['extra', 'client_secret'], key: 'solonclaw.channels.dingtalk.clientSecret' },
    { path: ['extra', 'robot_code'], key: 'solonclaw.channels.dingtalk.robotCode' },
  ],
  wecom: [
    { path: ['enabled'], key: 'solonclaw.channels.wecom.enabled' },
    { path: ['extra', 'bot_id'], key: 'solonclaw.channels.wecom.botId' },
    { path: ['extra', 'secret'], key: 'solonclaw.channels.wecom.secret' },
  ],
  weixin: [
    { path: ['enabled'], key: 'solonclaw.channels.weixin.enabled' },
    { path: ['token'], key: 'solonclaw.channels.weixin.token' },
    { path: ['extra', 'account_id'], key: 'solonclaw.channels.weixin.accountId' },
  ],
  qqbot: [
    { path: ['enabled'], key: 'solonclaw.channels.qqbot.enabled' },
    { path: ['extra', 'app_id'], key: 'solonclaw.channels.qqbot.appId' },
    { path: ['extra', 'client_secret'], key: 'solonclaw.channels.qqbot.clientSecret' },
  ],
  yuanbao: [
    { path: ['enabled'], key: 'solonclaw.channels.yuanbao.enabled' },
    { path: ['extra', 'app_id'], key: 'solonclaw.channels.yuanbao.appId' },
    { path: ['extra', 'app_secret'], key: 'solonclaw.channels.yuanbao.appSecret' },
  ],
}

function credentialValue(values: Record<string, any>, path: string[]): string | boolean | null | undefined {
  let current: any = values
  for (const part of path) {
    if (current == null || !(part in current)) return undefined
    current = current[part]
  }
  return current
}

function assignCredentialPreview(target: Record<string, any>, path: string[], value: string): void {
  if (path[0] === 'enabled') return
  let current = target
  for (let i = 0; i < path.length - 1; i += 1) {
    const part = path[i]
    current[part] = current[part] || {}
    current = current[part]
  }
  current[path[path.length - 1]] = value
}

function platformCredentials(
  platform: string,
  channelConfig: Record<string, any>,
  runtimeConfig: Record<string, WorkspaceConfigInfo>,
): Record<string, any> {
  const result: Record<string, any> = {
    enabled: configBoolean(channelConfig?.enabled),
  }
  for (const field of CHANNEL_CREDENTIAL_FIELDS[platform] || []) {
    assignCredentialPreview(result, field.path, configPreview(runtimeConfig, field.key))
  }
  return result
}


export async function fetchWorkspaceConfigItems(): Promise<Record<string, WorkspaceConfigInfo>> {
  return request<Record<string, WorkspaceConfigInfo>>('/api/workspace-config')
}

export async function setWorkspaceConfigItem(key: string, value: string): Promise<void> {
  const text = (value || '').trim()
  if (!text) {
    await request(`/api/workspace-config?key=${encodeURIComponent(key)}`, { method: 'DELETE' })
    return
  }
  await request('/api/workspace-config', {
    method: 'PUT',
    body: JSON.stringify({ key, value: text }),
  })
}

export async function revealWorkspaceConfigItem(key: string): Promise<string> {
  const data = await request<{ value: string }>('/api/workspace-config/reveal', {
    method: 'POST',
    body: JSON.stringify({ key }),
  })
  return data.value || ''
}

export async function fetchConfig(_sections?: string[]): Promise<AppConfig> {
  const [data, runtimeConfig] = await Promise.all([
    request<Record<string, any>>('/api/config'),
    request<Record<string, WorkspaceConfigInfo>>('/api/workspace-config'),
  ])
  return {
    display: {
      show_reasoning: !!data.display?.showReasoning,
      streaming: !!data.llm?.stream,
    },
    agent: {
      max_turns: data.react?.maxSteps,
    },
    memory: {
      memory_enabled: true,
      user_profile_enabled: true,
    },
    session_reset: {
      mode: data.scheduler?.enabled ? 'manual' : 'off',
    },
    privacy: {
      redact_pii: false,
    },
    wecom: data.channels?.wecom || {},
    feishu: data.channels?.feishu || {},
    dingtalk: data.channels?.dingtalk || {},
    weixin: data.channels?.weixin || {},
    qqbot: data.channels?.qqbot || {},
    yuanbao: data.channels?.yuanbao || {},
    platforms: {
      feishu: platformCredentials('feishu', data.channels?.feishu || {}, runtimeConfig),
      dingtalk: platformCredentials('dingtalk', data.channels?.dingtalk || {}, runtimeConfig),
      wecom: platformCredentials('wecom', data.channels?.wecom || {}, runtimeConfig),
      weixin: platformCredentials('weixin', data.channels?.weixin || {}, runtimeConfig),
      qqbot: platformCredentials('qqbot', data.channels?.qqbot || {}, runtimeConfig),
      yuanbao: platformCredentials('yuanbao', data.channels?.yuanbao || {}, runtimeConfig),
    },
  }
}

export async function updateConfigSection(
  section: string,
  values: Record<string, any>,
): Promise<void> {
  const current = await request<Record<string, any>>('/api/config')
  const next = { ...current }

  if (section === 'display') {
    next.display = {
      ...(next.display || {}),
      showReasoning: values.show_reasoning ?? next.display?.showReasoning,
    }
    next.llm = {
      ...(next.llm || {}),
      stream: values.streaming ?? next.llm?.stream,
    }
  } else if (section === 'agent') {
    next.react = {
      ...(next.react || {}),
      maxSteps: values.max_turns ?? next.react?.maxSteps,
    }
  } else if (
    section === 'wecom'
    || section === 'feishu'
    || section === 'dingtalk'
    || section === 'weixin'
    || section === 'qqbot'
    || section === 'yuanbao'
  ) {
    next.channels = {
      ...(next.channels || {}),
      [section]: {
        ...(next.channels?.[section] || {}),
        ...values,
      },
    }
  } else {
    return
  }

  await request('/api/config', {
    method: 'PUT',
    body: JSON.stringify({ config: next }),
  })
}

export async function saveCredentials(
  platform: string,
  values: Record<string, any>,
): Promise<void> {
  const entries = (CHANNEL_CREDENTIAL_FIELDS[platform] || [])
    .map(field => ({ key: field.key, value: credentialValue(values, field.path) }))
    .filter(entry => entry.value !== undefined)

  for (const entry of entries) {
    const raw = entry.value
    const text = typeof raw === 'boolean' ? String(raw) : (raw ?? '').toString().trim()
    if (!text) {
      await request(`/api/workspace-config?key=${encodeURIComponent(entry.key)}`, {
        method: 'DELETE',
      })
    } else {
      await request('/api/workspace-config', {
        method: 'PUT',
        body: JSON.stringify({ key: entry.key, value: text }),
      })
    }
  }
}

export interface WeixinQrCode {
  qrcode: string
  qrcode_url: string
}

export interface WeixinQrStatus {
  status: 'wait' | 'scaned' | 'scaned_but_redirect' | 'expired' | 'confirmed' | 'error'
  qrcode?: string
  qrcode_url?: string
  message?: string
  error_message?: string
  account_id?: string
  base_url?: string
}

export async function fetchWeixinQrCode(): Promise<WeixinQrCode> {
  const res = await fetchPlatformQrCode('weixin')
  return {
    qrcode: res.qrcode || '',
    qrcode_url: res.qrcode_url || '',
  }
}

export async function pollWeixinQrStatus(qrcode: string): Promise<WeixinQrStatus> {
  const res = await pollPlatformQrStatus('weixin', qrcode)
  return {
    status: res.status,
    qrcode: res.qrcode,
    qrcode_url: res.qrcode_url,
    message: res.message || '',
    error_message: res.error_message || '',
    account_id: res.account_id,
    base_url: res.base_url,
  }
}

export async function fetchPlatformQrCode(platform: ChannelQrPlatform): Promise<ChannelQrStatusView> {
  const res = await request<any>(`/api/gateway/setup/${encodeURIComponent(platform)}/qr`, { method: 'POST' })
  return normalizeChannelQrStatus(res)
}

export async function pollPlatformQrStatus(
  platform: ChannelQrPlatform,
  qrcode: string,
): Promise<ChannelQrStatusView> {
  const res = await request<any>(`/api/gateway/setup/${encodeURIComponent(platform)}/qr/${encodeURIComponent(qrcode)}`)
  return normalizeChannelQrStatus(res)
}

export async function saveWeixinCredentials(_data: {
  account_id: string
  token: string
  base_url?: string
}): Promise<void> {
  throw new Error('当前后端未开放微信凭证保存接口')
}
