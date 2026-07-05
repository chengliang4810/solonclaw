import { request } from '../client'
import type { ChannelQrPlatform, ChannelQrStatusView } from '@/shared/channelQr'
import { normalizeChannelQrStatus } from '@/shared/channelQr'

export interface DisplayConfig {
  personality?: string
  resume_display?: string
  show_reasoning?: boolean
  streaming?: boolean
}

export interface AgentConfig {
  max_turns?: number
  service_tier?: string
}

export interface GatewayConfig {
  processingReactionsEnabled?: boolean
}

export type PlatformCatalogItem = {
  readonly code: string
  readonly displayName?: string
  readonly iconKey?: string
  readonly order?: number
  readonly enabled?: boolean
}

export interface AppConfig {
  display?: DisplayConfig
  agent?: AgentConfig
  gateway?: GatewayConfig
  wecom?: Record<string, any>
  feishu?: Record<string, any>
  dingtalk?: Record<string, any>
  weixin?: Record<string, any>
  qqbot?: Record<string, any>
  yuanbao?: Record<string, any>
  platforms?: Record<string, any>
  platformCatalog?: readonly PlatformCatalogItem[]
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

type CredentialFieldSource = 'enabled' | 'token' | `extra.${string}`

interface CredentialFieldMapping {
  source: CredentialFieldSource
  key: string
}

const CHANNEL_CREDENTIAL_FIELDS: Record<string, CredentialFieldMapping[]> = {
  feishu: [
    { source: 'enabled', key: 'solonclaw.channels.feishu.enabled' },
    { source: 'extra.app_id', key: 'solonclaw.channels.feishu.appId' },
    { source: 'extra.app_secret', key: 'solonclaw.channels.feishu.appSecret' },
  ],
  dingtalk: [
    { source: 'enabled', key: 'solonclaw.channels.dingtalk.enabled' },
    { source: 'extra.client_id', key: 'solonclaw.channels.dingtalk.clientId' },
    { source: 'extra.client_secret', key: 'solonclaw.channels.dingtalk.clientSecret' },
    { source: 'extra.robot_code', key: 'solonclaw.channels.dingtalk.robotCode' },
  ],
  wecom: [
    { source: 'enabled', key: 'solonclaw.channels.wecom.enabled' },
    { source: 'extra.bot_id', key: 'solonclaw.channels.wecom.botId' },
    { source: 'extra.secret', key: 'solonclaw.channels.wecom.secret' },
  ],
  weixin: [
    { source: 'enabled', key: 'solonclaw.channels.weixin.enabled' },
    { source: 'token', key: 'solonclaw.channels.weixin.token' },
    { source: 'extra.account_id', key: 'solonclaw.channels.weixin.accountId' },
  ],
  qqbot: [
    { source: 'enabled', key: 'solonclaw.channels.qqbot.enabled' },
    { source: 'extra.app_id', key: 'solonclaw.channels.qqbot.appId' },
    { source: 'extra.client_secret', key: 'solonclaw.channels.qqbot.clientSecret' },
  ],
  yuanbao: [
    { source: 'enabled', key: 'solonclaw.channels.yuanbao.enabled' },
    { source: 'extra.app_id', key: 'solonclaw.channels.yuanbao.appId' },
    { source: 'extra.app_secret', key: 'solonclaw.channels.yuanbao.appSecret' },
  ],
}

function credentialValue(values: Record<string, any>, source: CredentialFieldSource): string | boolean | null | undefined {
  if (source === 'enabled') return values.enabled
  if (source === 'token') return values.token
  return values.extra?.[source.slice('extra.'.length)]
}

function normalizePlatformCatalog(value: unknown): readonly PlatformCatalogItem[] {
  if (!Array.isArray(value)) return []
  return value
    .filter((item): item is Record<string, unknown> => item !== null && typeof item === 'object')
    .map(item => ({
      code: typeof item.code === 'string' ? item.code : '',
      displayName: typeof item.displayName === 'string' ? item.displayName : undefined,
      iconKey: typeof item.iconKey === 'string' ? item.iconKey : undefined,
      order: typeof item.order === 'number' ? item.order : undefined,
      enabled: typeof item.enabled === 'boolean' ? item.enabled : undefined,
    }))
    .filter(item => item.code.length > 0)
}


export async function fetchWorkspaceConfigItems(): Promise<Record<string, WorkspaceConfigInfo>> {
  return request<Record<string, WorkspaceConfigInfo>>('/api/workspace-config')
}

export async function fetchConfigDiagnostics(): Promise<Record<string, any>> {
  return request<Record<string, any>>('/api/config/diagnostics')
}

export async function fetchConfigSchema(): Promise<Record<string, any>> {
  return request<Record<string, any>>('/api/config/schema')
}

export async function fetchConfigDefaults(): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>('/api/config/defaults')
}

export async function fetchRawConfig(): Promise<Record<string, any>> {
  return request<Record<string, any>>('/api/config/raw')
}

export async function saveRawConfig(yamlText: string): Promise<Record<string, any>> {
  return request<Record<string, any>>('/api/config/raw', {
    method: 'PUT',
    body: JSON.stringify({ yaml_text: yamlText }),
  })
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
    gateway: {
      processingReactionsEnabled: configBoolean(data.gateway?.processingReactionsEnabled),
    },
    wecom: data.channels?.wecom || {},
    feishu: data.channels?.feishu || {},
    dingtalk: data.channels?.dingtalk || {},
    weixin: data.channels?.weixin || {},
    qqbot: data.channels?.qqbot || {},
    yuanbao: data.channels?.yuanbao || {},
    platformCatalog: normalizePlatformCatalog(data.platform_catalog),
    platforms: {
      feishu: {
        enabled: configBoolean(data.channels?.feishu?.enabled),
        extra: {
          app_id: configPreview(runtimeConfig, 'solonclaw.channels.feishu.appId'),
          app_secret: configPreview(runtimeConfig, 'solonclaw.channels.feishu.appSecret'),
        },
      },
      dingtalk: {
        enabled: configBoolean(data.channels?.dingtalk?.enabled),
        extra: {
          client_id: configPreview(runtimeConfig, 'solonclaw.channels.dingtalk.clientId'),
          client_secret: configPreview(runtimeConfig, 'solonclaw.channels.dingtalk.clientSecret'),
          robot_code: configPreview(runtimeConfig, 'solonclaw.channels.dingtalk.robotCode'),
        },
      },
      wecom: {
        enabled: configBoolean(data.channels?.wecom?.enabled),
        extra: {
          bot_id: configPreview(runtimeConfig, 'solonclaw.channels.wecom.botId'),
          secret: configPreview(runtimeConfig, 'solonclaw.channels.wecom.secret'),
        },
      },
      weixin: {
        enabled: configBoolean(data.channels?.weixin?.enabled),
        token: configPreview(runtimeConfig, 'solonclaw.channels.weixin.token'),
        extra: {
          account_id: configPreview(runtimeConfig, 'solonclaw.channels.weixin.accountId'),
        },
      },
      qqbot: {
        enabled: configBoolean(data.channels?.qqbot?.enabled),
        extra: {
          app_id: configPreview(runtimeConfig, 'solonclaw.channels.qqbot.appId'),
          client_secret: configPreview(runtimeConfig, 'solonclaw.channels.qqbot.clientSecret'),
        },
      },
      yuanbao: {
        enabled: configBoolean(data.channels?.yuanbao?.enabled),
        extra: {
          app_id: configPreview(runtimeConfig, 'solonclaw.channels.yuanbao.appId'),
          app_secret: configPreview(runtimeConfig, 'solonclaw.channels.yuanbao.appSecret'),
        },
      },
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
  } else if (section === 'gateway') {
    next.gateway = {
      ...(next.gateway || {}),
      processingReactionsEnabled: values.processingReactionsEnabled ?? next.gateway?.processingReactionsEnabled,
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
    .map(field => ({ key: field.key, value: credentialValue(values, field.source) }))
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
