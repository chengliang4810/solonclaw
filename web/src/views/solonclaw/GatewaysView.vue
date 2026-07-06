<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { Spin, Tag } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useGatewayStore } from '@/stores/solonclaw/gateways'

const { t } = useI18n()
const gatewayStore = useGatewayStore()
const refreshTimer = ref<ReturnType<typeof setInterval> | null>(null)

onMounted(() => {
  refreshStatus()
  refreshTimer.value = setInterval(refreshStatus, 15000)
})

onUnmounted(() => {
  if (refreshTimer.value) {
    clearInterval(refreshTimer.value)
  }
})

function refreshStatus() {
  gatewayStore.fetchStatus()
}
</script>

<template>
  <div class="gateways-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('gateways.title') }}</h2>
        <p class="header-subtitle">{{ t('gateways.description') }}</p>
      </div>
    </header>

    <div class="gateways-content">
      <Spin :spinning="gatewayStore.loading" size="large">
        <div v-if="gatewayStore.loadError" class="gateway-load-error">
          <strong>{{ t('common.fetchFailed') }}</strong>
          <span>{{ gatewayStore.loadError }}</span>
        </div>

        <div v-if="gatewayStore.gateways.length === 0 && !gatewayStore.loadError" class="empty-state">
          {{ t('gateways.emptyState') }}
        </div>

        <div v-if="gatewayStore.gateways.length > 0" class="gateway-list">
          <div v-for="gw in gatewayStore.gateways" :key="gw.profile" class="gateway-card">
            <div class="gateway-info">
              <div class="gateway-name">{{ gw.profile }}</div>
              <div class="gateway-meta">
                <span class="meta-item">{{ gw.port ? `${gw.host}:${gw.port}` : gw.host }}</span>
                <span v-if="gw.pid" class="meta-item">PID: {{ gw.pid }}</span>
              </div>
            </div>
            <div class="gateway-actions">
              <Tag :color="gw.running ? 'success' : 'default'" size="small" shape="round">
                {{ gw.running ? t('gateways.running') : t('gateways.stopped') }}
              </Tag>
            </div>
          </div>
        </div>
      </Spin>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.gateways-view {
  height: calc(100 * var(--vh));
  display: flex;
  flex-direction: column;
}

.gateways-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.empty-state {
  text-align: center;
  color: $text-muted;
  padding: 40px 0;
}

.gateway-load-error {
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

.gateway-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.gateway-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  background-color: $bg-card;
  border: 1px solid $border-color;
  border-radius: $radius-md;
  transition: border-color $transition-fast;

  &:hover {
    border-color: $text-muted;
  }
}

.gateway-name {
  font-size: 14px;
  font-weight: 600;
  color: $text-primary;
  margin-bottom: 4px;
}

.gateway-meta {
  display: flex;
  gap: 12px;
}

.meta-item {
  font-size: 12px;
  color: $text-muted;
}

.gateway-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
