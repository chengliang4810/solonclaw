<script setup lang="ts">
import { Button } from 'antdv-next'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useUsageStore } from '@/stores/solonclaw/usage'
import { fetchInsightsOverview, type InsightsOverview } from '@/api/solonclaw/insights'
import StatCards from '@/components/solonclaw/usage/StatCards.vue'
import ModelBreakdown from '@/components/solonclaw/usage/ModelBreakdown.vue'
import DailyTrend from '@/components/solonclaw/usage/DailyTrend.vue'

const { t } = useI18n()
const usageStore = useUsageStore()
const insights = ref<InsightsOverview | null>(null)
const insightsLoading = ref(false)
const insightCards = computed(() => [
  { label: t('usage.insightSessions'), value: insights.value?.sessions?.total ?? '-' },
  { label: t('usage.insightTrackedSkills'), value: insights.value?.skills?.tracked ?? '-' },
  { label: t('usage.insightActiveSkills'), value: insights.value?.skills?.active ?? '-' },
  { label: t('usage.insightMemory'), value: insights.value?.runtime?.usedMemoryMb ? `${insights.value.runtime.usedMemoryMb} MB` : '-' },
])

async function loadInsights() {
  insightsLoading.value = true
  try {
    insights.value = await fetchInsightsOverview()
  } finally {
    insightsLoading.value = false
  }
}

onMounted(() => {
  usageStore.loadUsage()
  loadInsights()
})
</script>

<template>
  <div class="usage-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('usage.title') }}</h2>
        <p class="header-subtitle">{{ t('usage.description') }}</p>
      </div>
      <Button size="small" type="text" :loading="usageStore.isLoading" @click="usageStore.loadUsage()">
        {{ t('usage.refresh') }}
      </Button>
    </header>

    <div class="usage-content">
      <section class="insights-panel">
        <div class="insights-title">
          <h3>{{ t('usage.insightsTitle') }}</h3>
          <Button size="small" type="text" :loading="insightsLoading" @click="loadInsights">
            {{ t('usage.refresh') }}
          </Button>
        </div>
        <div class="insights-grid">
          <div v-for="item in insightCards" :key="item.label" class="insight-card">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
      </section>

      <div v-if="usageStore.isLoading && !usageStore.analytics" class="usage-loading">
        {{ t('common.loading') }}
      </div>

      <template v-else-if="usageStore.analytics && usageStore.totalSessions > 0">
        <StatCards />
        <ModelBreakdown />
        <DailyTrend />
      </template>

      <div v-else class="usage-empty">
        {{ t('usage.noData') }}
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.usage-view {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.usage-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  max-width: 960px;
  margin: 0 auto;
  width: 100%;
  scrollbar-width: none;
  -ms-overflow-style: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.usage-loading,
.usage-empty {
  text-align: center;
  padding: 60px 0;
  color: $text-muted;
  font-size: 14px;
}

.insights-panel {
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  background: $bg-primary;
  padding: 14px;
  margin-bottom: 16px;
}

.insights-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.insights-title h3 {
  margin: 0;
  font-size: 14px;
  color: $text-primary;
}

.insights-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(120px, 1fr));
  gap: 10px;
}

.insight-card {
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  background: $bg-secondary;
  padding: 10px;
}

.insight-card span {
  display: block;
  font-size: 12px;
  color: $text-muted;
}

.insight-card strong {
  display: block;
  margin-top: 6px;
  font-size: 18px;
  color: $text-primary;
}

@media (max-width: 720px) {
  .insights-grid {
    grid-template-columns: repeat(2, minmax(120px, 1fr));
  }
}
</style>
