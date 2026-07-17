import { request } from '../client'

export interface CuratorReportSummary {
  report_id: string
  status: string
  summary?: string
  report_path?: string
  started_at: number
  finished_at: number
}

export interface CuratorReportDetail extends CuratorReportSummary {
  report?: unknown
  report_json?: unknown
}

export interface CuratorRunResult {
  report_id?: string
  status?: string
  summary?: string
}

export interface CuratorStatus {
  enabled: boolean
  paused: boolean
  aiEnabled: boolean
  aiProvider?: string
  aiModel?: string
  aiTimeoutSeconds?: number
  trackedSkills?: number
  lastRunAt?: number
}

export interface CuratorEvaluation {
  mode?: 'ai' | 'deterministic_fallback'
  verdict?: string
  confidence?: number
  evidenceRefs?: string[]
  issues?: string[]
  recommendations?: string[]
  fallbackReason?: string
  ruleOverride?: string
  provider?: string
  model?: string
}

export interface CuratorReportItem {
  name: string
  status?: string
  action?: string
  ageDays?: number
  activityCount?: number
  evidenceSessionCount?: number
  evidenceSessions?: string[]
  suggestions?: string[]
  evaluation?: CuratorEvaluation
}

export interface CuratorImprovement {
  improvement_id: string
  skill_name: string
  action?: string
  summary?: string
  needs_review?: boolean
  created_at?: number
  source?: string
  confidence?: number
  evidence_refs?: string[]
  fallback_reason?: string
}

export interface CuratorSuggestionResult {
  skill?: string
  suggestion?: string
  status?: string
}

export async function fetchCuratorReports(limit = 20): Promise<CuratorReportSummary[]> {
  const res = await request<{ reports: CuratorReportSummary[] }>(`/api/curator?limit=${limit}`)
  return res.reports || []
}

export async function runCurator(force = true): Promise<CuratorRunResult> {
  return request<CuratorRunResult>(`/api/curator/run?force=${force}`, { method: 'POST' })
}

export async function fetchCuratorStatus(): Promise<CuratorStatus> {
  return request<CuratorStatus>('/api/curator/status')
}

export async function setCuratorPaused(paused: boolean): Promise<CuratorStatus> {
  return request<CuratorStatus>(`/api/curator/${paused ? 'pause' : 'resume'}`, { method: 'POST' })
}

export async function fetchCuratorReport(reportId: string): Promise<CuratorReportDetail> {
  return request<CuratorReportDetail>(`/api/curator/${encodeURIComponent(reportId)}`)
}

export async function fetchCuratorImprovements(limit = 20): Promise<CuratorImprovement[]> {
  const res = await request<{ improvements: CuratorImprovement[] }>(`/api/curator/improvements?limit=${limit}`)
  return res.improvements || []
}

export function markCuratorSuggestion(skill: string, suggestion: string, action: 'apply' | 'ignore') {
  return request<CuratorSuggestionResult>(`/api/curator/${action}`, {
    method: 'POST',
    body: JSON.stringify({ skill, suggestion }),
  })
}
