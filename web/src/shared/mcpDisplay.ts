export type McpStatusTone = 'success' | 'warning' | 'error' | 'default'

export interface McpTransportOption {
  readonly label: string
  readonly value: string
}

export const MCP_TRANSPORT_OPTIONS: readonly McpTransportOption[] = [
  { label: 'stdio', value: 'stdio' },
  { label: 'streamable', value: 'streamable' },
  { label: 'streamable_stateless', value: 'streamable_stateless' },
  { label: 'sse', value: 'sse' },
] as const

/**
 * antdv Select 的 options 类型是可变数组；调用方拿到拷贝，避免组件意外改写共享常量。
 */
export function mcpTransportOptions(): McpTransportOption[] {
  return MCP_TRANSPORT_OPTIONS.map(option => ({ ...option }))
}

const SUCCESS_STATUSES = new Set(['ready', 'connected', 'authenticated'])
const ERROR_STATUSES = new Set(['error', 'blocked', 'expired'])
const WARNING_STATUSES = new Set(['pending', 'configured'])

/**
 * MCP 服务和 OAuth 返回同一组状态 token；这里只负责映射 Tag 色彩，不改变协议值本身。
 */
export function mcpStatusTone(status: string | null | undefined): McpStatusTone {
  if (SUCCESS_STATUSES.has(status || '')) return 'success'
  if (ERROR_STATUSES.has(status || '')) return 'error'
  if (WARNING_STATUSES.has(status || '')) return 'warning'
  return 'default'
}

/**
 * MCP 时间字段来自后端毫秒时间戳；空值保持页面原有占位符。
 */
export function mcpTimestampText(value?: number | null, fallback = '-'): string {
  if (!value) return fallback
  return new Date(value).toLocaleString()
}
