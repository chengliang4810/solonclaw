import { request } from '../client'

type TuiRpcMethod =
  | 'setup.status'
  | 'model.options'
  | 'model.save_key'
  | 'channel.options'
  | 'channel.status'
  | 'channel.save'
  | 'channel.qr.start'
  | 'channel.qr.get'
  | 'config.get'

interface TuiRpcErrorBody {
  readonly code: number
  readonly message: string
}

interface TuiRpcResponse<T> {
  readonly jsonrpc: string
  readonly id?: string | number | null
  readonly result?: T
  readonly error?: TuiRpcErrorBody
}

export interface TuiSetupStatus {
  readonly provider_configured?: boolean
  readonly provider?: string
  readonly model?: string
  readonly api_key?: string
  readonly workspace_config?: string
  readonly [key: string]: unknown
}

export interface TuiModelProvider {
  readonly slug?: string
  readonly name?: string
  readonly authenticated?: boolean
  readonly is_current?: boolean
  readonly total_models?: number
  readonly default_model?: string
  readonly dialect?: string
  readonly base_url?: string
  readonly warning?: string
  readonly models?: readonly string[]
  readonly [key: string]: unknown
}

export interface TuiModelOptions {
  readonly model?: string
  readonly provider?: string
  readonly providers?: readonly TuiModelProvider[]
  readonly [key: string]: unknown
}

export interface TuiChannelStatus {
  readonly key?: string
  readonly label?: string
  readonly channel?: string
  readonly enabled?: boolean
  readonly configured?: boolean
  readonly status?: string
  readonly required_keys?: readonly string[]
  readonly allowed_keys?: readonly string[]
  readonly qr_supported?: boolean
  readonly fields?: readonly TuiChannelField[]
  readonly required_configured?: Readonly<Record<string, unknown>>
  readonly [key: string]: unknown
}

export interface TuiChannelField {
  readonly key?: string
  readonly label?: string
  readonly description?: string
  readonly required?: boolean
  readonly secret?: boolean
  readonly [key: string]: unknown
}

export interface TuiChannelOptions {
  readonly channels?: readonly TuiChannelStatus[]
  readonly mtime?: number
  readonly [key: string]: unknown
}

export interface TuiFullConfig {
  readonly key?: string
  readonly config?: Readonly<Record<string, unknown>>
  readonly mtime?: number
  readonly [key: string]: unknown
}

export interface TuiRuntimeOverview {
  readonly setup: TuiSetupStatus
  readonly models: TuiModelOptions
  readonly channels: TuiChannelOptions
  readonly config: TuiFullConfig
}

export interface TuiModelSaveKeyResult {
  readonly ok?: boolean
  readonly error?: string
  readonly detail?: unknown
  readonly provider?: TuiModelProvider
  readonly [key: string]: unknown
}

export interface TuiChannelSaveResult {
  readonly ok?: boolean
  readonly saved?: boolean
  readonly channel?: string
  readonly status?: string
  readonly enabled?: boolean
  readonly values?: Readonly<Record<string, unknown>>
  readonly mtime?: number
  readonly error?: string
  readonly detail?: unknown
  readonly [key: string]: unknown
}

export interface TuiChannelQrResult {
  readonly ok?: boolean
  readonly channel?: string
  readonly status?: string
  readonly ticket?: string
  readonly qrcode?: string
  readonly qr_code?: string
  readonly device_code?: string
  readonly qrcode_url?: string
  readonly qr_image_url?: string
  readonly qrcode_img_content?: string
  readonly qr_url?: string
  readonly message?: string
  readonly error?: string
  readonly error_code?: string
  readonly error_message?: string
  readonly domain?: string
  readonly [key: string]: unknown
}

export class TuiRuntimeRpcError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'TuiRuntimeRpcError'
    this.code = code
  }
}

async function callTuiRuntimeRpc<T>(
  method: TuiRpcMethod,
  params: Readonly<Record<string, unknown>> = {},
): Promise<T> {
  const response = await request<TuiRpcResponse<T>>('/api/tui/rpc', {
    method: 'POST',
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: method,
      method,
      params,
    }),
  })

  if (response.error) {
    throw new TuiRuntimeRpcError(response.error.code, response.error.message)
  }
  if (response.result === undefined) {
    throw new TuiRuntimeRpcError(-32000, 'TUI runtime RPC returned an empty result')
  }
  return response.result
}

export async function fetchTuiRuntimeOverview(): Promise<TuiRuntimeOverview> {
  const [setup, models, channels, config] = await Promise.all([
    callTuiRuntimeRpc<TuiSetupStatus>('setup.status'),
    callTuiRuntimeRpc<TuiModelOptions>('model.options'),
    callTuiRuntimeRpc<TuiChannelOptions>('channel.options'),
    callTuiRuntimeRpc<TuiFullConfig>('config.get', { key: 'full' }),
  ])
  return { setup, models, channels, config }
}

export function saveTuiRuntimeModelApiKey(
  slug: string,
  apiKey: string,
): Promise<TuiModelSaveKeyResult> {
  return callTuiRuntimeRpc<TuiModelSaveKeyResult>('model.save_key', { slug, api_key: apiKey })
}

export function fetchTuiRuntimeChannelStatus(channel: string): Promise<TuiChannelStatus> {
  return callTuiRuntimeRpc<TuiChannelStatus>('channel.status', { channel })
}

export function saveTuiRuntimeChannelConfig(
  channel: string,
  values: Readonly<Record<string, string | boolean>>,
): Promise<TuiChannelSaveResult> {
  return callTuiRuntimeRpc<TuiChannelSaveResult>('channel.save', { channel, values })
}

export function startTuiRuntimeChannelQr(channel: string): Promise<TuiChannelQrResult> {
  return callTuiRuntimeRpc<TuiChannelQrResult>('channel.qr.start', { channel })
}

export function fetchTuiRuntimeChannelQr(
  channel: string,
  ticket: string,
): Promise<TuiChannelQrResult> {
  return callTuiRuntimeRpc<TuiChannelQrResult>('channel.qr.get', { channel, ticket })
}
