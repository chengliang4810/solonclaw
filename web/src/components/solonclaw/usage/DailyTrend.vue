<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useUsageStore } from '@/stores/solonclaw/usage'
import {
  formatUsageCost,
  formatUsageDateLabel,
  formatUsageTokens,
  latestUsageRows,
  maxUsageValue,
  usageBarPercent,
} from '@/shared/usageFormat'
import { dailyUsageTrendMetrics, type DailyUsageTrendMetricKey } from '@/shared/usageMetrics'

const { t } = useI18n()
const usageStore = useUsageStore()

interface DailyUsageRow {
  readonly date: string
  readonly tokens: number
  readonly cacheRead: number
  readonly cacheWrite: number
  readonly sessions: number
  readonly costMicros: number
  readonly currency: string
  readonly pricingAvailable: boolean
}

interface UsageTrendColumn {
  readonly key: DailyUsageTrendMetricKey
  readonly label: string
  readonly format: (row: DailyUsageRow) => string
}

type DailyUsageTrendFormatter = (row: DailyUsageRow) => string

const maxTokens = computed(() => maxUsageValue(usageStore.dailyUsage.map(d => d.tokens)))
const tableRows = computed(() => latestUsageRows(usageStore.dailyUsage))
const dailyUsageTrendFormatters = {
  tokens: row => formatUsageTokens(row.tokens),
  cacheRead: row => formatUsageTokens(row.cacheRead),
  cacheWrite: row => formatUsageTokens(row.cacheWrite),
  cost: row => row.pricingAvailable
    ? formatUsageCost(row.costMicros, row.currency)
    : t('usage.unpriced'),
  sessions: row => String(row.sessions),
} satisfies Record<DailyUsageTrendMetricKey, DailyUsageTrendFormatter>
const usageTrendColumns = computed<readonly UsageTrendColumn[]>(() =>
  dailyUsageTrendMetrics.map(metric => ({
    key: metric.key,
    label: t(metric.labelKey),
    format: dailyUsageTrendFormatters[metric.key],
  })),
)
</script>

<template>
  <div class="daily-trend">
    <h3 class="section-title">{{ t('usage.dailyTrend') }}</h3>

    <div class="bar-chart">
      <div
        v-for="d in usageStore.dailyUsage"
        :key="d.date"
        class="bar-col"
      >
        <div class="bar-track">
          <div
            class="bar-fill"
            :style="{ height: usageBarPercent(d.tokens, maxTokens) }"
          />
        </div>
        <div class="bar-tooltip">
          <div class="tooltip-date">{{ d.date }}</div>
          <div
            v-for="column in usageTrendColumns"
            :key="column.key"
            class="tooltip-row"
          >
            {{ column.label }}: {{ column.format(d) }}
          </div>
        </div>
      </div>
    </div>
    <div class="bar-dates">
      <span>{{ formatUsageDateLabel(usageStore.dailyUsage[0]?.date || '') }}</span>
      <span>{{ formatUsageDateLabel(usageStore.dailyUsage[usageStore.dailyUsage.length - 1]?.date || '') }}</span>
    </div>

    <div class="trend-table">
      <table>
        <thead>
          <tr>
            <th>{{ t('usage.date') }}</th>
            <th v-for="column in usageTrendColumns" :key="column.key">{{ column.label }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in tableRows" :key="d.date">
            <td>{{ d.date }}</td>
            <td v-for="column in usageTrendColumns" :key="column.key">
              {{ column.format(d) }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.daily-trend {
  background: $bg-card;
  border: 1px solid $border-color;
  border-radius: $radius-md;
  padding: 16px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: $text-secondary;
  margin: 0 0 12px;
}

.bar-chart {
  display: flex;
  gap: 2px;
  margin-bottom: 16px;
}

.bar-col {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.bar-track {
  width: 100%;
  height: 140px;
  background: $bg-secondary;
  border-radius: 2px 2px 0 0;
  display: flex;
  align-items: flex-end;
}

.bar-fill {
  width: 100%;
  background: $text-primary;
  border-radius: 2px 2px 0 0;
  min-height: 0;
  transition: height 0.3s ease;

  .dark & {
    background: #66bb6a;
  }
}

.bar-col {
  position: relative;
}

.bar-tooltip {
  display: none;
  position: absolute;
  bottom: calc(100% + 8px);
  left: 50%;
  transform: translateX(-50%);
  background: $text-primary;
  color: var(--text-on-accent);
  padding: 6px 10px;
  border-radius: $radius-sm;
  font-size: 11px;
  white-space: nowrap;
  z-index: 10;
  pointer-events: none;

  &::after {
    content: '';
    position: absolute;
    top: 100%;
    left: 50%;
    transform: translateX(-50%);
    border: 5px solid transparent;
    border-top-color: $text-primary;
  }
}

.bar-col:hover .bar-tooltip {
  display: block;
}

.tooltip-date {
  font-weight: 600;
  margin-bottom: 2px;
}

.tooltip-row {
  font-size: 10px;
  opacity: 0.85;
  line-height: 1.5;
}

.bar-label {
  display: none;
}

.bar-dates {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: $text-muted;
  margin-top: 4px;
  margin-bottom: 16px;
}

.trend-table {
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

thead {
  position: sticky;
  top: 0;
}

th {
  text-align: left;
  padding: 8px 10px;
  font-weight: 600;
  color: $text-muted;
  border-bottom: 1px solid $border-color;
  background: $bg-card;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.3px;
}

td {
  padding: 6px 10px;
  color: $text-secondary;
  border-bottom: 1px solid $border-light;
  font-family: $font-code;
  font-size: 11px;
}

tr:last-child td {
  border-bottom: none;
}
</style>
