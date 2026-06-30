<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useUsageStore } from '@/stores/solonclaw/usage'
import { formatUsageCost, formatUsageTokens, usageCostFormatPresets } from '@/shared/usageFormat'

const { t } = useI18n()
const usageStore = useUsageStore()
</script>

<template>
  <div class="stat-cards">
    <div class="stat-card">
      <div class="stat-label">{{ t('usage.totalTokens') }}</div>
      <div class="stat-value">{{ formatUsageTokens(usageStore.totalTokens) }}</div>
      <div class="stat-sub">
        {{ formatUsageTokens(usageStore.totalInputTokens) }} {{ t('usage.inputTokens') }} /
        {{ formatUsageTokens(usageStore.totalOutputTokens) }} {{ t('usage.outputTokens') }}
      </div>
    </div>
    <div class="stat-card">
      <div class="stat-label">{{ t('usage.totalSessions') }}</div>
      <div class="stat-value">{{ usageStore.totalSessions }}</div>
      <div class="stat-sub">{{ t('usage.avgPerDay', { n: usageStore.avgSessionsPerDay.toFixed(1) }) }}</div>
    </div>
    <div class="stat-card">
      <div class="stat-label">{{ t('usage.cacheHitRate') }}</div>
      <div class="stat-value">{{ usageStore.cacheHitRate !== null ? usageStore.cacheHitRate.toFixed(1) + '%' : '--' }}</div>
      <div class="stat-sub" v-if="usageStore.cacheHitRate !== null">
        {{ formatUsageTokens(usageStore.totalCacheReadTokens) }} {{ t('usage.cacheRead') }} /
        {{ formatUsageTokens(usageStore.totalCacheWriteTokens) }} {{ t('usage.cacheWrite') }}
      </div>
    </div>
    <div class="stat-card">
      <div class="stat-label">{{ t('usage.estimatedCost') }}</div>
      <div class="stat-value">
        {{ usageStore.pricingAvailable ? formatUsageCost(usageStore.totalCostMicros, usageStore.currency, usageCostFormatPresets.summary) : '--' }}
      </div>
      <div class="stat-sub">
        {{ usageStore.pricingAvailable ? t('usage.priced') : t('usage.unpriced') }}
        <span v-if="usageStore.unpricedTokens > 0">
          / {{ formatUsageTokens(usageStore.unpricedTokens) }} {{ t('usage.unpricedTokens') }}
        </span>
      </div>
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
