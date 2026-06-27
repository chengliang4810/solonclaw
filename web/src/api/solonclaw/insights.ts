import { request } from '../client'

export interface InsightsOverview {
  sessions?: Record<string, unknown>
  skills?: Record<string, unknown>
  runtime?: Record<string, unknown>
}

export interface SkillInsight {
  state?: string
  count?: number
  pinned?: boolean
  [key: string]: unknown
}

export type SkillInsights = Record<string, SkillInsight>

export async function fetchInsightsOverview(): Promise<InsightsOverview> {
  return request<InsightsOverview>('/api/insights/overview')
}

export async function fetchSkillInsights(): Promise<SkillInsights> {
  return request<SkillInsights>('/api/insights/skills')
}
