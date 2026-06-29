import { request } from '../client'

type TuiRpcMethod = 'setup.status' | 'model.options' | 'channel.options' | 'config.get'

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
  readonly required_configured?: Readonly<Record<string, unknown>>
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
