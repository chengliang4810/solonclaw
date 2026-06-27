export interface SessionSearchBase {
  id: string
  source: string
  model: string
  title: string | null
  preview?: string
  started_at: number
  ended_at: number | null
  last_active?: number
  message_count: number
  tool_call_count: number
  input_tokens: number
  output_tokens: number
  cache_read_tokens: number
  cache_write_tokens: number
  reasoning_tokens: number
  provider: string | null
  parent_session_id?: string | null
  branch_name?: string | null
  compressed_summary?: string | null
  last_compression_at?: number
  last_compression_input_tokens?: number
  compression_failure_count?: number
}

export interface DashboardSearchResultRow {
  session_id: string
  title?: string | null
  match_preview?: string | null
  summary?: string | null
  updated_at?: number | null
  branch_name?: string | null
  channel?: string | null
}

export function mapDashboardSearchResult<T extends SessionSearchBase>(
  row: DashboardSearchResultRow,
  base: T | undefined,
  rank: number,
): T & { matched_message_id: null, snippet: string, rank: number } {
  const updatedAt = row.updated_at ? Math.floor(row.updated_at / 1000) : 0
  return {
    ...(base || {
      id: row.session_id,
      source: row.channel || 'local',
      model: '',
      title: null,
      preview: '',
      started_at: updatedAt,
      ended_at: null,
      last_active: updatedAt,
      message_count: 0,
      tool_call_count: 0,
      input_tokens: 0,
      output_tokens: 0,
      cache_read_tokens: 0,
      cache_write_tokens: 0,
      reasoning_tokens: 0,
      provider: null,
      parent_session_id: null,
      branch_name: null,
      compressed_summary: null,
      last_compression_at: 0,
      last_compression_input_tokens: 0,
      compression_failure_count: 0,
    } as T),
    title: row.title || base?.title || null,
    source: base?.source || row.channel || 'local',
    branch_name: row.branch_name || base?.branch_name || null,
    started_at: updatedAt || base?.started_at || 0,
    last_active: updatedAt || base?.last_active || 0,
    matched_message_id: null,
    snippet: row.match_preview || row.summary || '',
    rank,
  }
}
