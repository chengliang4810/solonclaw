import { request } from '../client'

export type InsightSection = Record<string, unknown>

export interface InsightsOverview {
  sessions?: InsightSection
  skills?: InsightSection
  runtime?: InsightSection
}

export type InsightsSkills = Record<string, InsightSection>

export async function fetchInsightsOverview(): Promise<InsightsOverview> {
  return request<InsightsOverview>('/api/insights/overview')
}

export async function fetchInsightsSkills(): Promise<InsightsSkills> {
  return request<InsightsSkills>('/api/insights/skills')
}
