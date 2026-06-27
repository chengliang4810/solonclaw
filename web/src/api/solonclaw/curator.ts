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
  report_json?: unknown
}

export interface CuratorRunResult {
  report_id?: string
  status?: string
  summary?: string
}

export async function fetchCuratorReports(limit = 20): Promise<CuratorReportSummary[]> {
  const res = await request<{ reports: CuratorReportSummary[] }>(`/api/curator?limit=${limit}`)
  return res.reports || []
}

export async function runCurator(force = true): Promise<CuratorRunResult> {
  return request<CuratorRunResult>(`/api/curator/run?force=${force}`, { method: 'POST' })
}

export async function fetchCuratorReport(reportId: string): Promise<CuratorReportDetail> {
  return request<CuratorReportDetail>(`/api/curator/${reportId}`)
}
