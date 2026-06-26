<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NTag } from 'naive-ui'
import { onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useUsageStore } from '@/stores/solonclaw/usage'
import StatCards from '@/components/solonclaw/usage/StatCards.vue'
import ModelBreakdown from '@/components/solonclaw/usage/ModelBreakdown.vue'
import DailyTrend from '@/components/solonclaw/usage/DailyTrend.vue'
import { formatLocalDateTimeMs as formatTime } from '@/shared/session-display'

const { t } = useI18n()
const usageStore = useUsageStore()
const overview = computed(() => usageStore.insights || {})

onMounted(() => {
  usageStore.loadUsage()
})

function asNumber(value: unknown): number {
  return typeof value === 'number' ? value : 0
}

</script>

<template>
  <div class="usage-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('usage.title') }}</h2>
        <p class="header-subtitle">{{ t('usage.description') }}</p>
      </div>
      <NButton size="small" quaternary :loading="usageStore.isLoading" @click="usageStore.loadUsage()">
        {{ t('usage.refresh') }}
      </NButton>
    </header>

    <div class="usage-content">
      <div v-if="usageStore.isLoading && !usageStore.analytics" class="usage-loading">
        {{ t('common.loading') }}
      </div>

      <template v-else>
        <section v-if="usageStore.insights" class="insights-panel">
          <div class="insights-card">
            <span>{{ t('usage.persistedSessions') }}</span>
            <strong>{{ asNumber(overview.sessions?.total) }}</strong>
          </div>
          <div class="insights-card">
            <span>{{ t('usage.trackedSkills') }}</span>
            <strong>{{ asNumber(overview.skills?.tracked) }}</strong>
          </div>
          <div class="insights-card">
            <span>{{ t('usage.activeSkills') }}</span>
            <strong>{{ asNumber(overview.skills?.active) }}</strong>
          </div>
          <div class="insights-card">
            <span>{{ t('usage.usedMemory') }}</span>
            <strong>{{ asNumber(overview.runtime?.usedMemoryMb) }} MB</strong>
          </div>
        </section>

        <template v-if="usageStore.analytics && usageStore.totalSessions > 0">
          <StatCards />
          <ModelBreakdown />
          <DailyTrend />
        </template>

        <section v-if="usageStore.skillInsightRows.length" class="skill-insights">
          <h3>{{ t('usage.skillInsights') }}</h3>
          <div class="skill-table">
            <div class="skill-row skill-row--head">
              <span>{{ t('usage.skillName') }}</span>
              <span>{{ t('usage.views') }}</span>
              <span>{{ t('usage.invokes') }}</span>
              <span>{{ t('usage.state') }}</span>
              <span>{{ t('usage.lastActive') }}</span>
            </div>
            <div v-for="skill in usageStore.skillInsightRows" :key="skill.name" class="skill-row">
              <strong>{{ skill.name }}</strong>
              <span>{{ skill.views }}</span>
              <span>{{ skill.invokes }}</span>
              <span>
                <NTag size="small" :type="skill.state === 'active' ? 'success' : skill.state === 'stale' ? 'warning' : 'default'">
                  {{ skill.state }}
                </NTag>
              </span>
              <span>{{ formatTime(skill.lastActiveAt) }}</span>
            </div>
          </div>
        </section>

        <div v-if="!usageStore.insights && (!usageStore.analytics || usageStore.totalSessions === 0)" class="usage-empty">
          {{ t('usage.noData') }}
        </div>
      </template>
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
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 20px;
}

.insights-card,
.skill-insights {
  background: $bg-card;
  border: 1px solid $border-color;
  border-radius: $radius-md;
}

.insights-card {
  padding: 14px;

  span {
    display: block;
    color: $text-muted;
    font-size: 12px;
  }

  strong {
    display: block;
    margin-top: 4px;
    color: $text-primary;
    font-size: 20px;
  }
}

.skill-insights {
  margin-top: 20px;
  padding: 16px;

  h3 {
    font-size: 14px;
    font-weight: 600;
    margin-bottom: 12px;
  }
}

.skill-table {
  overflow-x: auto;
}

.skill-row {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 80px 80px 100px 180px;
  gap: 12px;
  align-items: center;
  padding: 9px 0;
  border-top: 1px solid $border-light;
  color: $text-secondary;
  font-size: 12px;
  min-width: 660px;

  strong {
    color: $text-primary;
    font-weight: 500;
  }
}

.skill-row--head {
  border-top: 0;
  color: $text-muted;
  font-size: 11px;
  text-transform: uppercase;
}

@media (max-width: 768px) {
  .insights-panel {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 480px) {
  .insights-panel {
    grid-template-columns: 1fr;
  }
}
</style>
