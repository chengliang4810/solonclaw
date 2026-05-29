import { fetchUsageAnalytics, type UsageAnalytics } from '@/api/jimuqu/usage'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

interface DailyUsage {
  date: string
  tokens: number
  cacheRead: number
  cacheWrite: number
  cacheTotal: number
  sessions: number
  costMicros: number
  currency: string
  pricingAvailable: boolean
  unpricedTokens: number
  backfillApproximate: boolean
}

interface ModelUsage {
  model: string
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  cacheTokens: number
  totalTokens: number
  sessions: number
  costMicros: number
  currency: string
  pricingAvailable: boolean
  unpricedTokens: number
  backfillApproximate: boolean
}

export const useUsageStore = defineStore('usage', () => {
  const analytics = ref<UsageAnalytics | null>(null)
  const isLoading = ref(false)

  async function loadUsage() {
    isLoading.value = true
    try {
      analytics.value = await fetchUsageAnalytics(30)
    } catch (err) {
      console.error('Failed to load usage analytics:', err)
    } finally {
      isLoading.value = false
    }
  }

  const totalInputTokens = computed(() => analytics.value?.totals.total_input || 0)

  const totalOutputTokens = computed(() => analytics.value?.totals.total_output || 0)

  const totalSessions = computed(() => analytics.value?.totals.total_sessions || 0)

  const totalCacheReadTokens = computed(() => analytics.value?.totals.total_cache_read || 0)

  const totalCacheWriteTokens = computed(() => analytics.value?.totals.total_cache_write || 0)

  const totalCacheTokens = computed(() => totalCacheReadTokens.value + totalCacheWriteTokens.value)

  const totalCostMicros = computed(() => analytics.value?.totals.total_cost_micros || 0)

  const currency = computed(() => analytics.value?.totals.currency || '')

  const pricingAvailable = computed(() => analytics.value?.totals.pricing_available || false)

  const unpricedTokens = computed(() => analytics.value?.totals.unpriced_total_tokens || 0)

  const backfillApproximate = computed(() => analytics.value?.totals.backfill_approximate || false)

  const totalPromptTokens = computed(() =>
    totalInputTokens.value + totalCacheReadTokens.value + totalCacheWriteTokens.value,
  )

  const totalTokens = computed(() => totalPromptTokens.value + totalOutputTokens.value)

  const cacheHitRate = computed(() => {
    const total = totalPromptTokens.value
    if (total === 0) return null
    return ((totalCacheReadTokens.value / total) * 100)
  })

  const modelUsage = computed<ModelUsage[]>(() => {
    return (analytics.value?.by_model || [])
      .map((item) => ({
        model: item.model || 'unknown',
        inputTokens: item.input_tokens || 0,
        outputTokens: item.output_tokens || 0,
        cacheReadTokens: item.cache_read_tokens || 0,
        cacheWriteTokens: item.cache_write_tokens || 0,
        cacheTokens: (item.cache_read_tokens || 0) + (item.cache_write_tokens || 0),
        totalTokens:
          (item.input_tokens || 0)
          + (item.output_tokens || 0)
          + (item.cache_read_tokens || 0)
          + (item.cache_write_tokens || 0),
        sessions: item.sessions || 0,
        costMicros: item.cost_micros || 0,
        currency: item.currency || '',
        pricingAvailable: item.pricing_available || false,
        unpricedTokens: item.unpriced_total_tokens || 0,
        backfillApproximate: item.backfill_approximate || false,
      }))
      .sort((a, b) => b.totalTokens - a.totalTokens)
  })

  const dailyUsage = computed<DailyUsage[]>(() => {
    return (analytics.value?.daily || []).map((item) => ({
      date: item.day,
      tokens:
        (item.input_tokens || 0)
        + (item.output_tokens || 0)
        + (item.cache_read_tokens || 0)
        + (item.cache_write_tokens || 0),
      cacheRead: item.cache_read_tokens || 0,
      cacheWrite: item.cache_write_tokens || 0,
      cacheTotal: (item.cache_read_tokens || 0) + (item.cache_write_tokens || 0),
      sessions: item.sessions || 0,
      costMicros: item.cost_micros || 0,
      currency: item.currency || '',
      pricingAvailable: item.pricing_available || false,
      unpricedTokens: item.unpriced_total_tokens || 0,
      backfillApproximate: item.backfill_approximate || false,
    }))
  })

  const avgSessionsPerDay = computed(() => {
    const days = Math.max(1, dailyUsage.value.length || 30)
    return totalSessions.value / days
  })

  return {
    analytics,
    isLoading,
    loadUsage,
    totalInputTokens,
    totalOutputTokens,
    totalTokens,
    totalSessions,
    totalCacheReadTokens,
    totalCacheWriteTokens,
    totalCacheTokens,
    totalCostMicros,
    currency,
    pricingAvailable,
    unpricedTokens,
    backfillApproximate,
    cacheHitRate,
    modelUsage,
    dailyUsage,
    avgSessionsPerDay,
  }
})
