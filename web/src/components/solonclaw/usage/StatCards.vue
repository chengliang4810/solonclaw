<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useUsageStore } from '@/stores/solonclaw/usage'
import { formatUsageCost, formatUsageTokens, usageCostFormatPresets } from '@/shared/usageFormat'
import { usageStatCardMetrics, type UsageStatCardMetricKey } from '@/shared/usageMetrics'

const { t } = useI18n()
const usageStore = useUsageStore()

interface UsageStatCardItem {
  readonly key: UsageStatCardMetricKey
  readonly label: string
  readonly value: string
  readonly subtext: string
}

interface UsageStatCardDisplay {
  readonly value: string
  readonly subtext: string
}

const EMPTY_METRIC = '--'

const statCardItems = computed<readonly UsageStatCardItem[]>(() => {
  const inputOutputSubtext = [
    `${formatUsageTokens(usageStore.totalInputTokens)} ${t('usage.inputTokens')}`,
    `${formatUsageTokens(usageStore.totalOutputTokens)} ${t('usage.outputTokens')}`,
  ].join(' / ')
  const cacheSubtext = usageStore.cacheHitRate === null
    ? ''
    : [
        `${formatUsageTokens(usageStore.totalCacheReadTokens)} ${t('usage.cacheRead')}`,
        `${formatUsageTokens(usageStore.totalCacheWriteTokens)} ${t('usage.cacheWrite')}`,
      ].join(' / ')
  const costValue = usageStore.pricingAvailable
    ? formatUsageCost(
        usageStore.totalCostMicros,
        usageStore.currency,
        usageCostFormatPresets.summary,
      )
    : EMPTY_METRIC
  const unpricedSubtext = usageStore.unpricedTokens > 0
    ? ` / ${formatUsageTokens(usageStore.unpricedTokens)} ${t('usage.unpricedTokens')}`
    : ''

  const displayByMetric = {
    totalTokens: {
      value: formatUsageTokens(usageStore.totalTokens),
      subtext: inputOutputSubtext,
    },
    totalSessions: {
      value: String(usageStore.totalSessions),
      subtext: t('usage.avgPerDay', { n: usageStore.avgSessionsPerDay.toFixed(1) }),
    },
    cacheHitRate: {
      value: usageStore.cacheHitRate !== null
        ? `${usageStore.cacheHitRate.toFixed(1)}%`
        : EMPTY_METRIC,
      subtext: cacheSubtext,
    },
    estimatedCost: {
      value: costValue,
      subtext: `${
        usageStore.pricingAvailable ? t('usage.priced') : t('usage.unpriced')
      }${unpricedSubtext}`,
    },
  } satisfies Record<UsageStatCardMetricKey, UsageStatCardDisplay>

  return usageStatCardMetrics.map(metric => ({
    key: metric.key,
    label: t(metric.labelKey),
    ...displayByMetric[metric.key],
  }))
})
</script>

<template>
  <div class="stat-cards">
    <div class="stat-card" v-for="item in statCardItems" :key="item.key">
      <div class="stat-label">{{ item.label }}</div>
      <div class="stat-value">{{ item.value }}</div>
      <div v-if="item.subtext" class="stat-sub">{{ item.subtext }}</div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.stat-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 20px;
}

.stat-card {
  background: $bg-card;
  border: 1px solid $border-color;
  border-radius: $radius-md;
  padding: 16px;
}

.stat-label {
  font-size: 12px;
  color: $text-muted;
  margin-bottom: 6px;
}

.stat-value {
  font-size: 22px;
  font-weight: 600;
  color: $text-primary;
  line-height: 1.2;
}

.stat-sub {
  font-size: 11px;
  color: $text-muted;
  margin-top: 4px;
}

@media (max-width: 768px) {
  .stat-cards {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 480px) {
  .stat-cards {
    grid-template-columns: 1fr;
  }
}
</style>
