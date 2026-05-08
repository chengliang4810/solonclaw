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

export interface SecurityAuditRequest {
  action: string
  toolName?: string
  command?: string
  url?: string
  path?: string
  writeLike?: boolean
  argsJson?: string
}

export interface SecurityAuditFinding {
  source?: string
  ruleId?: string
  severity?: string
  message?: string
}

export interface SecurityAuditResult {
  success?: boolean
  action?: string
  decision?: 'allow' | 'warn' | 'block' | 'error' | string
  summary?: string
  findings?: SecurityAuditFinding[]
  [key: string]: unknown
}

export async function fetchDiagnostics(): Promise<Diagnostics> {
  return request<Diagnostics>('/api/diagnostics')
}

export async function auditSecurity(payload: SecurityAuditRequest): Promise<SecurityAuditResult> {
  return request<SecurityAuditResult>('/api/diagnostics/security-audit', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}
