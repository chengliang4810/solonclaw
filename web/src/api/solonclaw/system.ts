import { request } from '../client'

export interface HealthResponse {
  status: string
  version?: string
  webui_version?: string
  webui_latest?: string
  node_version?: string
}

export interface ProviderRecord {
  providerKey: string
  name: string
  baseUrl: string
  defaultModel: string
  dialect: string
  hasApiKey: boolean
  isDefault: boolean
}

export interface DialectCatalogItem {
  value: string
  labelKey?: string
  baseUrlPlaceholder?: string
}

export interface FallbackProvider {
  provider: string
  model: string
}

export interface ModelsHealthProvider {
  provider: string
  status: string
  checked_at: number
}

export interface ModelsHealthResponse {
  providers: ModelsHealthProvider[]
}

export interface RuntimeModelStatus {
  provider: string
  model: string
  dialect: string
  role: string
  status: string
  context_window?: number
  max_output?: number
  pricing?: ModelPricingStatus
  group_label?: string
}

export interface ModelPricingStatus {
  currency?: string
  input?: string
  output?: string
  cache?: string
  cache_read?: string
  cache_write?: string
  reasoning?: string
  source?: string
  source_url?: string
  pricing_version?: string
  fetched_at?: number
  free?: boolean
}

export interface RuntimeModelsResponse {
  models: RuntimeModelStatus[]
}

export interface ProviderValidationRequest {
  providerKey?: string
  baseUrl: string
  apiKey?: string
  dialect: string
  model?: string
  defaultModel?: string
}

export interface ProviderValidationResponse {
  ok: boolean
  reachable: boolean
  status: string
  message: string
  url: string
  models?: string[]
}

export interface AvailableModelGroup {
  provider: string
  providerKey: string
  label: string
  base_url: string
  models: string[]
  dialect: string
  has_api_key: boolean
  isDefault: boolean
}

export interface AvailableModelsResponse {
  default: string
  default_provider: string
  groups: AvailableModelGroup[]
  allProviders: AvailableModelGroup[]
  fallbackProviders: FallbackProvider[]
  dialectCatalog: DialectCatalogItem[]
}

export interface CustomProvider {
  providerKey: string
  name: string
  baseUrl: string
  apiKey?: string
  defaultModel: string
  dialect: string
}

interface DashboardStatus {
  version?: string
  latest_version?: string
  latest_tag?: string
}

export interface RuntimeStatusResponse {
  runtime_status?: {
    multimodal?: Record<string, unknown>
    pricing?: Record<string, unknown>
    [key: string]: unknown
  }
  runtime_capabilities?: Record<string, unknown>
}

interface ProvidersPayload {
  providers: ProviderRecord[]
  defaultProviderKey: string
  defaultModel: string
  fallbackProviders: FallbackProvider[]
  dialectCatalog?: DialectCatalogItem[]
}

export interface DashboardModelInfo {
  model: string
  provider: string
  providerKey: string
  providerLabel: string
  dialect: string
  baseUrl: string
  fallbackProviders: FallbackProvider[]
  auto_context_length?: number
  effective_context_length?: number
}

export async function checkHealth(): Promise<HealthResponse> {
  const [health, status] = await Promise.all([
    request<{ ok?: boolean; service?: string }>('/health'),
    request<DashboardStatus>('/api/status'),
  ])

  return {
    status: health.ok ? 'ok' : 'error',
    version: status.version,
    webui_version: status.version,
    webui_latest: status.latest_version || status.latest_tag || status.version,
    node_version: '',
  }
}

function toGroup(provider: ProviderRecord, defaultModel: string): AvailableModelGroup {
  const model = provider.defaultModel || defaultModel || ''
  return {
    provider: provider.providerKey,
    providerKey: provider.providerKey,
    label: provider.name || provider.providerKey,
    base_url: provider.baseUrl,
    models: model ? [model] : [],
    dialect: provider.dialect,
    has_api_key: provider.hasApiKey,
    isDefault: provider.isDefault,
  }
}

export async function fetchAvailableModels(): Promise<AvailableModelsResponse> {
  const payload = await request<ProvidersPayload>('/api/providers')
  const groups = payload.providers.map(p => toGroup(p, payload.defaultModel))
  return {
    default: payload.defaultModel || '',
    default_provider: payload.defaultProviderKey || '',
    groups,
    allProviders: groups,
    fallbackProviders: payload.fallbackProviders || [],
    dialectCatalog: payload.dialectCatalog || [],
  }
}

export async function fetchModelInfo(): Promise<DashboardModelInfo> {
  return request<DashboardModelInfo>('/api/model/info')
}

export async function fetchModelsHealth(): Promise<ModelsHealthResponse> {
  return request<ModelsHealthResponse>('/api/models/health')
}

export async function fetchRuntimeModels(): Promise<RuntimeModelsResponse> {
  return request<RuntimeModelsResponse>('/api/models')
}

export async function fetchRuntimeStatus(): Promise<RuntimeStatusResponse> {
  return request<RuntimeStatusResponse>('/api/status')
}

export async function validateProvider(data: ProviderValidationRequest): Promise<ProviderValidationResponse> {
  return request<ProviderValidationResponse>('/api/providers/validate', {
    method: 'POST',
    body: JSON.stringify(data),
  })
}

export async function updateDefaultModel(data: {
  default: string
  provider?: string
}): Promise<void> {
  await request('/api/model/default', {
    method: 'PUT',
    body: JSON.stringify({
      providerKey: data.provider || '',
      model: data.default,
    }),
  })
}

export async function addCustomProvider(data: CustomProvider): Promise<void> {
  await request('/api/providers', {
    method: 'POST',
    body: JSON.stringify(data),
  })
}

export async function fetchProviderModels(data: {
  providerKey?: string
  baseUrl: string
  apiKey?: string
  dialect: string
  model?: string
  defaultModel?: string
}): Promise<{ url: string; models: string[] }> {
  return request<{ url: string; models: string[] }>('/api/providers/models', {
    method: 'POST',
    body: JSON.stringify(data),
  })
}

export async function removeCustomProvider(name: string): Promise<void> {
  await request(`/api/providers/${encodeURIComponent(name)}`, {
    method: 'DELETE',
  })
}

export async function updateProvider(poolKey: string, data: {
  name?: string
  baseUrl?: string
  apiKey?: string
  defaultModel?: string
  dialect?: string
}): Promise<void> {
  await request(`/api/providers/${encodeURIComponent(poolKey)}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  })
}

export async function updateFallbackProviders(fallbackProviders: FallbackProvider[]): Promise<void> {
  await request('/api/model/fallbacks', {
    method: 'PUT',
    body: JSON.stringify({ fallbackProviders }),
  })
}
