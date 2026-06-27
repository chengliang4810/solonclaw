import { request } from '../client'

export interface CuratorReportSummary {
  report_id: string
  status: string
  summary?: string
  report_path?: string
  started_at: number
  finished_at: number
}

export interface CuratorImprovement {
  improvement_id: string
  session_id?: string
  run_id?: string
  skill_name: string
  action?: string
  summary?: string
  changed_files?: unknown
  evidence?: unknown
  needs_review?: boolean
  created_at: number
}

export async function fetchCuratorReports(limit = 20): Promise<CuratorReportSummary[]> {
  const res = await request<{ reports: CuratorReportSummary[] }>(`/api/curator?limit=${limit}`)
  return res.reports || []
}

export async function runCurator(force = true) {
  return request(`/api/curator/run?force=${force}`, { method: 'POST' })
}

export async function fetchCuratorReport(reportId: string) {
  return request(`/api/curator/${reportId}`)
}

export async function fetchCuratorImprovements(limit = 20): Promise<CuratorImprovement[]> {
  const res = await request<{ improvements: CuratorImprovement[] }>(`/api/curator/improvements?limit=${limit}`)
  return res.improvements || []
}

export async function applyCuratorSuggestion(skill: string, suggestion: string) {
  return request('/api/curator/apply', {
    method: 'POST',
    body: JSON.stringify({ skill, suggestion }),
  })
}

export async function ignoreCuratorSuggestion(skill: string, suggestion: string) {
  return request('/api/curator/ignore', {
    method: 'POST',
    body: JSON.stringify({ skill, suggestion }),
  })
}
