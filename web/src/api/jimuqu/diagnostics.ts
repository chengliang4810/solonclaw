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
  decision?: string
  blocking?: boolean
  approval_required?: boolean
  suggested_action?: string
  message?: string
}

export interface SecurityAuditResult {
  success?: boolean
  action?: string
  decision?: 'allow' | 'warn' | 'block' | 'error' | string
  blocking?: boolean
  approval_required?: boolean
  summary?: string
  findings?: SecurityAuditFinding[]
  [key: string]: unknown
}

export interface PendingApproval {
  session_id: string
  source_ref?: string
  title?: string
  branch_name?: string
  updated_at?: number
  approval_id?: string
  selector?: string
  tool_name?: string
  description?: string
  pattern_key?: string
  pattern_keys?: string[]
  rule_sources?: string[]
  command_preview?: string
  command_hash?: string
  created_at?: number
  expires_at?: number
  expires_in_seconds?: number
  expired?: boolean
  scopes?: string
  scope_options?: string[]
  permanent_allowed?: boolean
  permanent_disabled_reason?: string
}

export interface PendingApprovalsResult {
  count: number
  items: PendingApproval[]
  session_scan_limit?: number
  scanned_sessions?: number
  truncated?: boolean
  session_scan_truncated?: boolean
  available?: boolean
  code?: string
  message?: string
}

export interface ApprovalAuditEvent {
  event_id: string
  session_id?: string
  event_type?: 'request' | 'response' | string
  choice?: string
  approver?: string
  tool_name?: string
  command_hash?: string
  command_preview?: string
  description?: string
  pattern_keys?: string[]
  created_at?: number
  approval_created_at?: number
  approval_expires_at?: number
}

export interface ApprovalHistoryResult {
  count: number
  items: ApprovalAuditEvent[]
}

export interface AlwaysApproval {
  approval_id?: string
  tool_name?: string
  pattern_key?: string
}

export interface AlwaysApprovalsResult {
  count: number
  items: AlwaysApproval[]
}

export interface PendingSlashConfirm {
  confirm_id: string
  confirm_ref?: string
  source_ref?: string
  command_preview?: string
  prompt_preview?: string
  allow_always?: boolean
  action_options?: string[]
  created_at?: number
  expires_at?: number
  expires_in_seconds?: number
  expired?: boolean
}

export interface PendingSlashConfirmsResult {
  count: number
  items: PendingSlashConfirm[]
}

export interface ResolveSlashConfirmRequest {
  confirmId: string
  action: 'approve' | 'always' | 'deny'
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

export async function fetchApprovalHistory(limit = 100): Promise<ApprovalHistoryResult> {
  return request<ApprovalHistoryResult>(`/api/diagnostics/approvals/history?limit=${limit}`)
}

export async function fetchAlwaysApprovals(limit = 100): Promise<AlwaysApprovalsResult> {
  return request<AlwaysApprovalsResult>(`/api/diagnostics/approvals/always?limit=${limit}`)
}

export async function revokeAlwaysApproval(approvalId: string): Promise<ResolveApprovalResult> {
  return request<ResolveApprovalResult>('/api/diagnostics/approvals/always/revoke', {
    method: 'POST',
    body: JSON.stringify({ approvalId }),
  })
}

export async function fetchPendingSlashConfirms(limit = 100): Promise<PendingSlashConfirmsResult> {
  return request<PendingSlashConfirmsResult>(`/api/diagnostics/slash-confirms?limit=${limit}`)
}

export async function resolveApproval(payload: ResolveApprovalRequest): Promise<ResolveApprovalResult> {
  return request<ResolveApprovalResult>('/api/diagnostics/approvals/resolve', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export async function resolveSlashConfirm(payload: ResolveSlashConfirmRequest): Promise<ResolveApprovalResult> {
  return request<ResolveApprovalResult>('/api/diagnostics/slash-confirms/resolve', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}
