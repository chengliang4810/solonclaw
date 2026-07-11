import { dashboardFetch, getApiKey, getBaseUrlValue, request } from '../client'
import { fetchAvailableModels } from './system'

export interface ProfileGatewayStatus {
  profile: string
  home: string
  running: boolean
  pid?: number | null
  port?: number | null
  state: Record<string, unknown>
  pid_file: string
  state_file: string
  log_file: string
}

export interface ProfileDistribution {
  name?: string
  version?: string
  description?: string
  author?: string
  license?: string
  source?: string
  installed_at?: string
  updated_at?: string
  requires?: unknown
  distribution_owned?: string[]
  [key: string]: unknown
}

export interface SolonClawProfile {
  name: string
  active: boolean
  current: boolean
  home: string
  description: string
  description_auto?: boolean
  model: string
  provider?: string
  gateway: ProfileGatewayStatus
  skills_count: number
  config: string
  config_exists: boolean
  credentials: string
  credentials_exists: boolean
  soul: string
  soul_exists: boolean
  sessions: string
  memory_file: string
  memory_dir: string
  skills_dir: string
  mcp_config: string
  channels_config: string
  logs: string
  aliases: string[]
  distribution: ProfileDistribution
  no_bundled_skills: boolean
}

export interface ProfileMcpServerCreate {
  name: string
  url?: string
  command?: string
  args?: string[]
  env?: Record<string, string>
  auth?: string
}

export interface ProfileHubSkill {
  name: string
  description: string
  source: string
  identifier: string
  trust_level: string
  repo?: string | null
  tags?: string[]
}

export interface ProfileHubSearchResponse {
  results: ProfileHubSkill[]
  source_counts?: Record<string, number>
  timed_out?: string[]
  installed?: Record<string, unknown>
}

export interface ProfileModelChoice {
  provider: string
  model: string
  label: string
}

export interface ProfilesResponse {
  profiles: SolonClawProfile[]
  active: string
  current: string
}

export interface CreateProfileRequest {
  name: string
  clone_from?: string | null
  clone_from_default?: boolean
  clone_all?: boolean
  no_alias?: boolean
  no_skills?: boolean
  description?: string
  provider?: string
  model?: string
  mcp_servers?: ProfileMcpServerCreate[]
  keep_skills?: string[]
  hub_skills?: string[]
}

export interface CreateProfileResult extends Partial<SolonClawProfile> {
  ok?: boolean
  name: string
  path?: string
  model_set?: boolean
  mcp_written?: number
  skills_disabled?: number
  hub_installs?: Array<{ identifier: string; pid: number | null }>
}

export interface ProfileDescriptionResult {
  description: string
  description_auto: boolean
}

export interface ProfileDescribeAutoResult extends ProfileDescriptionResult {
  ok: boolean
  reason: string
}

export interface ProfileSoulResult {
  content: string
  exists: boolean
}

export interface ProfileGatewayOptions {
  args?: string[]
  force?: boolean
}

export interface InstallProfileDistributionRequest {
  source: string
  name?: string
  alias?: boolean
  force?: boolean
}

export async function fetchProfiles(): Promise<ProfilesResponse> {
  return request<ProfilesResponse>('/api/profiles')
}

export async function fetchProfile(name: string): Promise<SolonClawProfile> {
  return request<SolonClawProfile>(`/api/profiles/${encodeURIComponent(name)}`)
}

export async function createProfile(body: CreateProfileRequest): Promise<CreateProfileResult> {
  return request<CreateProfileResult>('/api/profiles', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export async function fetchProfileModelChoices(): Promise<ProfileModelChoice[]> {
  const response = await fetchAvailableModels()
  return response.allProviders.flatMap(provider =>
    provider.models.map(model => ({
      provider: provider.providerKey || provider.provider,
      model,
      label: `${provider.label} · ${model}`,
    })),
  )
}

export async function setActiveProfile(name: string): Promise<{ active: string; current: string }> {
  return request('/api/profiles/active', {
    method: 'POST',
    body: JSON.stringify({ name }),
  })
}

export async function renameProfile(name: string, newName: string): Promise<SolonClawProfile> {
  return request(`/api/profiles/${encodeURIComponent(name)}`, {
    method: 'PATCH',
    body: JSON.stringify({ new_name: newName }),
  })
}

export async function deleteProfile(name: string): Promise<void> {
  await request(`/api/profiles/${encodeURIComponent(name)}`, { method: 'DELETE' })
}

export async function fetchProfileGateway(name: string): Promise<ProfileGatewayStatus> {
  return request(`/api/profiles/${encodeURIComponent(name)}/gateway`)
}

export async function updateProfileDescription(name: string, description: string): Promise<ProfileDescriptionResult> {
  return request(`/api/profiles/${encodeURIComponent(name)}/description`, {
    method: 'PUT',
    body: JSON.stringify({ description }),
  })
}

export async function describeProfileAutomatically(name: string, overwrite = true): Promise<ProfileDescribeAutoResult> {
  return request(`/api/profiles/${encodeURIComponent(name)}/describe-auto`, {
    method: 'POST',
    body: JSON.stringify({ overwrite }),
  })
}

export async function fetchProfileSoul(name: string): Promise<ProfileSoulResult> {
  return request(`/api/profiles/${encodeURIComponent(name)}/soul`)
}

export async function updateProfileSoul(name: string, content: string): Promise<void> {
  await request(`/api/profiles/${encodeURIComponent(name)}/soul`, {
    method: 'PUT',
    body: JSON.stringify({ content }),
  })
}

export async function updateProfileModel(name: string, provider: string, model: string): Promise<{ provider: string; model: string }> {
  return request(`/api/profiles/${encodeURIComponent(name)}/model`, {
    method: 'PUT',
    body: JSON.stringify({ provider, model }),
  })
}

export async function fetchProfileSetupCommand(name: string): Promise<{ command: string }> {
  return request(`/api/profiles/${encodeURIComponent(name)}/setup-command`)
}

export async function openProfileTerminal(name: string): Promise<{ ok: boolean; command: string }> {
  return request(`/api/profiles/${encodeURIComponent(name)}/open-terminal`, {
    method: 'POST',
    body: JSON.stringify({}),
  })
}

export async function createProfileAlias(name: string, alias?: string): Promise<SolonClawProfile> {
  return request(`/api/profiles/${encodeURIComponent(name)}/alias`, {
    method: 'PUT',
    body: JSON.stringify({ alias: alias?.trim() || null }),
  })
}

export async function removeProfileAlias(name: string, alias?: string): Promise<SolonClawProfile> {
  return request(`/api/profiles/${encodeURIComponent(name)}/alias`, {
    method: 'DELETE',
    body: JSON.stringify({ alias: alias?.trim() || null }),
  })
}

export async function installProfileDistribution(body: InstallProfileDistributionRequest): Promise<SolonClawProfile> {
  return request('/api/profiles/install', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export async function fetchProfileDistribution(name: string): Promise<ProfileDistribution> {
  return request(`/api/profiles/${encodeURIComponent(name)}/distribution`)
}

export async function updateProfileDistribution(name: string, forceConfig = false): Promise<SolonClawProfile> {
  return request(`/api/profiles/${encodeURIComponent(name)}/distribution/update`, {
    method: 'POST',
    body: JSON.stringify({ force_config: forceConfig }),
  })
}

export async function startProfileGateway(name: string, options: ProfileGatewayOptions = {}): Promise<ProfileGatewayStatus> {
  return request(`/api/profiles/${encodeURIComponent(name)}/gateway/start`, {
    method: 'POST',
    body: JSON.stringify(options),
  })
}

export async function stopProfileGateway(name: string): Promise<ProfileGatewayStatus> {
  return request(`/api/profiles/${encodeURIComponent(name)}/gateway/stop`, {
    method: 'POST',
    body: JSON.stringify({}),
  })
}

export async function restartProfileGateway(name: string, options: ProfileGatewayOptions = {}): Promise<ProfileGatewayStatus> {
  return request(`/api/profiles/${encodeURIComponent(name)}/gateway/restart`, {
    method: 'POST',
    body: JSON.stringify(options),
  })
}

export async function searchProfileHubSkills(query: string, source = 'all', limit = 20): Promise<ProfileHubSearchResponse> {
  const params = new URLSearchParams({ q: query, source, limit: String(limit) })
  return request(`/api/skills/hub/search?${params.toString()}`)
}

export async function importProfile(file: File, name?: string): Promise<SolonClawProfile> {
  const form = new FormData()
  form.append('file', file)
  if (name?.trim()) form.append('name', name.trim())
  return request('/api/profiles/import', { method: 'POST', body: form })
}

export async function exportProfile(name: string): Promise<void> {
  const headers = new Headers()
  const apiKey = getApiKey()
  if (apiKey) headers.set('Authorization', `Bearer ${apiKey}`)

  const path = `/api/profiles/${encodeURIComponent(name)}/export`
  const response = await dashboardFetch(`${getBaseUrlValue()}${path}`, { headers })
  if (!response.ok) {
    const text = await response.text().catch(() => '')
    throw new Error(text || `Export failed: ${response.status}`)
  }

  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${name}.tar.gz`
  document.body.appendChild(anchor)
  try {
    anchor.click()
  } finally {
    document.body.removeChild(anchor)
    URL.revokeObjectURL(url)
  }
}
