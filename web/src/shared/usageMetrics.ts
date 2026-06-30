// 使用量页面共享的指标顺序与多语言键，展示值仍由各组件按场景计算。
export const usageStatCardMetrics = [
  { key: 'totalTokens', labelKey: 'usage.totalTokens' },
  { key: 'totalSessions', labelKey: 'usage.totalSessions' },
  { key: 'cacheHitRate', labelKey: 'usage.cacheHitRate' },
  { key: 'estimatedCost', labelKey: 'usage.estimatedCost' },
] as const

export type UsageStatCardMetricKey = typeof usageStatCardMetrics[number]['key']

export const dailyUsageTrendMetrics = [
  { key: 'tokens', labelKey: 'usage.tokens' },
  { key: 'cacheRead', labelKey: 'usage.cacheRead' },
  { key: 'cacheWrite', labelKey: 'usage.cacheWrite' },
  { key: 'cost', labelKey: 'usage.cost' },
  { key: 'sessions', labelKey: 'usage.sessions' },
] as const

export type DailyUsageTrendMetricKey = typeof dailyUsageTrendMetrics[number]['key']
