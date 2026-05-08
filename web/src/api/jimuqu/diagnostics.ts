import { request } from '../client'

export interface Diagnostics {
  runtime: Record<string, unknown>
  providers: Array<Record<string, unknown>>
  channels: Array<Record<string, unknown>>
  tools: { count: number; names: string[] }
  mcp: Record<string, unknown>
  security?: {
    approvals?: Record<string, unknown>
    policy?: Record<string, unknown>
    terminal?: Record<string, unknown>
  }
}

export async function fetchDiagnostics(): Promise<Diagnostics> {
  return request<Diagnostics>('/api/diagnostics')
}
