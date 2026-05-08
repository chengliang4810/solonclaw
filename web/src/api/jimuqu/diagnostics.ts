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

export interface PendingApproval {
  session_id: string
  source_key?: string
  title?: string
  branch_name?: string
  updated_at?: number
  approval_id?: string
  selector?: string
  tool_name?: string
  description?: string
  pattern_key?: string
  pattern_keys?: string[]
  command_preview?: string
  command_hash?: string
  approval_key?: string
  created_at?: number
  expires_at?: number
  scopes?: string
  permanent_allowed?: boolean
}

export interface PendingApprovalsResult {
  count: number
  items: PendingApproval[]
}

export interface ResolveApprovalRequest {
  sessionId: string
  approvalId?: string
  selector?: string
  action: 'approve' | 'deny'
  scope?: 'once' | 'session' | 'always'
  resume?: boolean
}

export interface ResolveApprovalResult {
  success: boolean
  code?: string
  message?: string
  action?: string
  session_id?: string
  resumed?: boolean
  reply?: Record<string, unknown>
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

export async function fetchPendingApprovals(limit = 100): Promise<PendingApprovalsResult> {
  return request<PendingApprovalsResult>(`/api/diagnostics/approvals?limit=${limit}`)
}

export async function resolveApproval(payload: ResolveApprovalRequest): Promise<ResolveApprovalResult> {
  return request<ResolveApprovalResult>('/api/diagnostics/approvals/resolve', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}
