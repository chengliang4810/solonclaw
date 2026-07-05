<script setup lang="ts">
import { Button } from 'antdv-next'
import { onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useUsageStore } from '@/stores/solonclaw/usage'
import StatCards from '@/components/solonclaw/usage/StatCards.vue'
import ModelBreakdown from '@/components/solonclaw/usage/ModelBreakdown.vue'
import DailyTrend from '@/components/solonclaw/usage/DailyTrend.vue'

const { t } = useI18n()
const usageStore = useUsageStore()
const refreshTimer = ref<ReturnType<typeof setInterval> | null>(null)

onMounted(() => {
  loadUsage()
  refreshTimer.value = setInterval(loadUsage, 30000)
})

onUnmounted(() => {
  if (refreshTimer.value) {
    clearInterval(refreshTimer.value)
  }
})

function loadUsage() {
  usageStore.loadUsage()
}
</script>

<template>
  <div class="usage-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('usage.title') }}</h2>
        <p class="header-subtitle">{{ t('usage.description') }}</p>
      </div>
      <Button size="small" type="text" :loading="usageStore.isLoading" @click="loadUsage">
        {{ t('usage.refresh') }}
      </Button>
    </header>

    <div class="usage-content">
      <div v-if="usageStore.loadError" class="usage-error">
        <strong>{{ t('common.fetchFailed') }}</strong>
        <span>{{ usageStore.loadError }}</span>
      </div>

      <div v-if="usageStore.isLoading && !usageStore.analytics" class="usage-loading">
        {{ t('common.loading') }}
      </div>

      <template v-if="usageStore.analytics && usageStore.totalSessions > 0">
        <StatCards />
        <ModelBreakdown />
        <DailyTrend />
      </template>

      <div v-else-if="!usageStore.loadError && !usageStore.isLoading" class="usage-empty">
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

.usage-error {
  display: grid;
  gap: 4px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid rgba(var(--error-rgb), 0.28);
  border-radius: $radius-sm;
  background: rgba(var(--error-rgb), 0.06);
  color: $error;
  font-size: 13px;

  span {
    overflow-wrap: anywhere;
  }
}
</style>
