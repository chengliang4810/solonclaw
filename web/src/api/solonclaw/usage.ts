import { request } from '../client'

interface UsageBreakdownItem {
  input_tokens: number
  output_tokens: number
  cache_read_tokens: number
  cache_write_tokens: number
  reasoning_tokens: number
  sessions: number
  cost_micros: number
  currency: string
  pricing_available: boolean
  unpriced_total_tokens: number
  backfill_approximate: boolean
}

export interface DailyUsageItem extends UsageBreakdownItem {
  day: string
}

export interface ModelUsageItem extends UsageBreakdownItem {
  model: string
}

export interface UsageTotals {
  total_input: number
  total_output: number
  total_cache_read: number
  total_cache_write: number
  total_reasoning: number
  total_sessions: number
  total_cost_micros: number
  currency: string
  pricing_available: boolean
  unpriced_total_tokens: number
  backfill_approximate: boolean
}

export interface UsageAnalytics {
  daily: DailyUsageItem[]
  by_model: ModelUsageItem[]
  totals: UsageTotals
}

export async function fetchUsageAnalytics(days = 30): Promise<UsageAnalytics> {
  return request<UsageAnalytics>(`/api/analytics/usage?days=${days}`)
}
