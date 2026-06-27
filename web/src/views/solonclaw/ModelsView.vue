<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Button, Spin, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import ProvidersPanel from '@/components/solonclaw/models/ProvidersPanel.vue'
import ProviderFormModal from '@/components/solonclaw/models/ProviderFormModal.vue'
import { useModelsStore } from '@/stores/solonclaw/models'
import { useAppStore } from '@/stores/solonclaw/app'
import { fetchModelHealth, type AvailableModelGroup, type ModelHealthProvider } from '@/api/solonclaw/system'

const { t } = useI18n()
const modelsStore = useModelsStore()
const appStore = useAppStore()
const showModal = ref(false)
const editingProvider = ref<AvailableModelGroup | null>(null)
const healthLoading = ref(false)
const healthProviders = ref<ModelHealthProvider[]>([])

onMounted(() => {
  modelsStore.fetchProviders()
})

function openCreateModal() {
  editingProvider.value = null
  showModal.value = true
}

function openEditModal(provider: AvailableModelGroup) {
  editingProvider.value = provider
  showModal.value = true
}

function handleModalClose() {
  showModal.value = false
  editingProvider.value = null
}

async function handleSaved() {
  await modelsStore.fetchProviders()
  appStore.loadModels()
  handleModalClose()
}

async function loadModelHealth() {
  healthLoading.value = true
  try {
    const result = await fetchModelHealth()
    healthProviders.value = result.providers || []
  } catch (err: any) {
    message.error(`${t('models.healthLoadFailed')}: ${err.message}`)
  } finally {
    healthLoading.value = false
  }
}

function healthLabel(status: string) {
  return t(`models.healthStatus.${status}`, status)
}

function healthTime(value?: number) {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}
</script>

<template>
  <div class="models-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('models.pageTitle') }}</h2>
        <p class="header-subtitle">{{ t('models.pageDescription') }}</p>
      </div>
      <Button type="primary" size="small" @click="openCreateModal">
        <template #icon>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        </template>
        {{ t('models.addProvider') }}
      </Button>
    </header>

    <div class="models-content">
      <section class="health-panel">
        <div class="health-header">
          <div>
            <h3>{{ t('models.healthTitle') }}</h3>
            <p>{{ t('models.healthDescription') }}</p>
          </div>
          <Button size="small" :loading="healthLoading" @click="loadModelHealth">{{ t('models.healthCheck') }}</Button>
        </div>
        <div v-if="healthProviders.length" class="health-list">
          <div v-for="item in healthProviders" :key="item.provider" class="health-row">
            <strong>{{ item.provider }}</strong>
            <span class="health-status" :class="item.status">{{ healthLabel(item.status) }}</span>
            <small>{{ healthTime(item.checked_at) }}</small>
          </div>
        </div>
        <div v-else class="health-empty">{{ t('models.healthEmpty') }}</div>
      </section>

      <Spin :spinning="modelsStore.loading && modelsStore.providers.length === 0">
        <ProvidersPanel @edit="openEditModal" />
      </Spin>
    </div>

    <ProviderFormModal
      v-if="showModal"
      :provider="editingProvider"
      @close="handleModalClose"
      @saved="handleSaved"
    />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.models-view {
  height: calc(100 * var(--vh));
  display: flex;
  flex-direction: column;
}

.models-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.health-panel {
  border-bottom: 1px solid $border-color;
  margin-bottom: 16px;
  padding-bottom: 16px;
}

.health-header,
.health-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.health-header {
  align-items: flex-start;
  margin-bottom: 10px;

  h3 {
    margin: 0;
    color: $text-primary;
    font-size: 15px;
  }

  p {
    margin: 4px 0 0;
    color: $text-muted;
    font-size: 12px;
  }
}

.health-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.health-row {
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  background: $bg-card;
  padding: 9px 12px;
  font-size: 13px;

  strong {
    color: $text-primary;
  }

  small {
    color: $text-muted;
  }
}

.health-status {
  color: $text-secondary;

  &.configured {
    color: $success;
  }

  &.missing_key,
  &.unreachable {
    color: $error;
  }
}

.health-empty {
  color: $text-muted;
  font-size: 12px;
}

@media (max-width: $breakpoint-mobile) {
  .health-header,
  .health-row {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
