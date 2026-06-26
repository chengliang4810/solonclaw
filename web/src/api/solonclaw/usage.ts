import { request } from '../client'

export interface DailyUsageItem {
  day: string
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

export interface ModelUsageItem {
  model: string
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

export interface InsightsOverview {
  sessions?: Record<string, unknown>
  skills?: Record<string, unknown>
  runtime?: Record<string, unknown>
}

export interface SkillInsight {
  state?: string
  viewCount?: number
  invokeCount?: number
  manageCount?: number
  pinned?: boolean
  lastViewedAt?: number
  lastInvokedAt?: number
  lastManagedAt?: number
}

export async function fetchUsageAnalytics(days = 30): Promise<UsageAnalytics> {
  return request<UsageAnalytics>(`/api/analytics/usage?days=${days}`)
}

export async function fetchInsightsOverview(): Promise<InsightsOverview> {
  return request<InsightsOverview>('/api/insights/overview')
}

export async function fetchSkillInsights(): Promise<Record<string, SkillInsight>> {
  return request<Record<string, SkillInsight>>('/api/insights/skills')
}
