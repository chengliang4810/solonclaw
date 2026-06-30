import type { TuiChannelStatus } from '@/api/solonclaw/tuiRuntime'

export type StatusTone = 'success' | 'warning' | 'default'

export interface TuiRuntimeDisplayText {
  readonly authenticated: string
  readonly configured: string
  readonly disabled: string
  readonly missingConfig: string
  readonly noData: string
  readonly unauthenticated: string
}

export function statusTone(status: string | undefined): StatusTone {
  if (status === 'configured') return 'success'
  if (status === 'missing_config') return 'warning'
  return 'default'
}

export function statusLabel(status: string | undefined, text: TuiRuntimeDisplayText): string {
  if (status === 'configured') return text.configured
  if (status === 'missing_config') return text.missingConfig
  if (status === 'disabled') return text.disabled
  return status || text.noData
}

export function providerAuthLabel(
  authenticated: boolean | undefined,
  text: TuiRuntimeDisplayText,
): string {
  return authenticated ? text.authenticated : text.unauthenticated
}

export function providerAuthColor(authenticated: boolean | undefined): StatusTone {
  return authenticated ? 'success' : 'default'
}

export function requiredSummary(channel: TuiChannelStatus): string {
  const requiredKeys = channel.required_keys ?? []
  const configured = channel.required_configured ?? {}
  const ready = requiredKeys.filter(key => configured[key] === true).length
  return `${ready}/${requiredKeys.length}`
}
