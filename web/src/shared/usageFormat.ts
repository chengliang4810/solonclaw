export interface UsageCostFormatOptions {
  readonly compactLargeAmounts: boolean
  readonly emptyNonPositive: boolean
}

export const usageCostFormatPresets = {
  summary: {
    compactLargeAmounts: true,
    emptyNonPositive: false,
  },
  detail: {
    compactLargeAmounts: false,
    emptyNonPositive: true,
  },
} as const satisfies Record<string, UsageCostFormatOptions>

export const DEFAULT_USAGE_TABLE_LIMIT = 30

export function formatUsageTokens(tokens: number): string {
  if (tokens >= 1000000) return `${(tokens / 1000000).toFixed(1)}M`
  if (tokens >= 1000) return `${(tokens / 1000).toFixed(1)}K`
  return String(tokens)
}

export function formatUsageCost(
  micros: number,
  currency: string,
  options: UsageCostFormatOptions = usageCostFormatPresets.detail,
): string {
  if (options.emptyNonPositive && micros <= 0) return '--'
  const amount = micros / 1000000
  const digits = options.compactLargeAmounts && amount >= 10 ? 2 : 4
  return `${currency || 'USD'} ${amount.toFixed(digits)}`
}

export function formatUsageDateLabel(date: string): string {
  return date.length > 5 ? date.slice(5) : date
}

export function maxUsageValue(values: readonly number[]): number {
  return Math.max(...values, 1)
}

export function usageBarPercent(value: number, maxValue: number): string {
  return `${(value / Math.max(maxValue, 1)) * 100}%`
}

export function latestUsageRows<T>(rows: readonly T[], limit: number = DEFAULT_USAGE_TABLE_LIMIT): T[] {
  return [...rows].reverse().slice(0, limit)
}
