<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { NButton, NTag, NSpin } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import ProvidersPanel from '@/components/solonclaw/models/ProvidersPanel.vue'
import ProviderFormModal from '@/components/solonclaw/models/ProviderFormModal.vue'
import { useModelsStore } from '@/stores/solonclaw/models'
import { useAppStore } from '@/stores/solonclaw/app'
import type { AvailableModelGroup } from '@/api/solonclaw/system'

const { t } = useI18n()
const modelsStore = useModelsStore()
const appStore = useAppStore()
const showModal = ref(false)
const editingProvider = ref<AvailableModelGroup | null>(null)
const healthByProvider = computed(() =>
  Object.fromEntries(modelsStore.modelHealth.map(item => [item.provider, item.status])),
)

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

function statusType(status?: string) {
  if (status === 'configured') return 'success'
  if (status === 'missing_key') return 'warning'
  return 'error'
}

function statusText(status?: string) {
  if (status === 'configured') return t('models.statusConfigured')
  if (status === 'missing_key') return t('models.statusMissingKey')
  if (status === 'unreachable') return t('models.statusUnreachable')
  return status || t('models.statusUnknown')
}

function runtimeHealth(provider: string, fallback?: string) {
  return healthByProvider.value[provider] || fallback
}

function priceText(pricing?: Record<string, unknown>) {
  if (!pricing) return t('models.priceUnknown')
  if (pricing.input === 'free' && pricing.output === 'free') return t('models.priceFree')
  const currency = String(pricing.currency || '')
  const input = pricing.input == null ? '-' : String(pricing.input)
  const output = pricing.output == null ? '-' : String(pricing.output)
  return `${currency} ${t('models.priceInputOutput', { input, output })}`.trim()
}
</script>

<template>
  <div class="models-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('models.pageTitle') }}</h2>
        <p class="header-subtitle">{{ t('models.pageDescription') }}</p>
      </div>
      <NButton type="primary" size="small" @click="openCreateModal">
        <template #icon>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        </template>
        {{ t('models.addProvider') }}
      </NButton>
    </header>

    <div class="models-content">
      <NSpin :show="modelsStore.loading && modelsStore.providers.length === 0">
        <section v-if="modelsStore.runtimeModels.length" class="runtime-panel">
          <div class="runtime-panel__header">
            <div>
              <h3>{{ t('models.runtimeModels') }}</h3>
              <p>{{ t('models.runtimeModelsDescription') }}</p>
            </div>
            <NTag size="small">{{ modelsStore.runtimeModels.length }}</NTag>
          </div>
          <div class="runtime-grid">
            <article v-for="model in modelsStore.runtimeModels" :key="`${model.provider}:${model.model}`" class="runtime-card">
              <div class="runtime-card__head">
                <div>
                  <strong>{{ model.model || '-' }}</strong>
                  <span>{{ model.provider }} · {{ model.dialect || '-' }}</span>
                </div>
                <NTag size="small" :type="statusType(runtimeHealth(model.provider, model.status))">
                  {{ statusText(runtimeHealth(model.provider, model.status)) }}
                </NTag>
              </div>
              <div class="runtime-meta">
                <span>{{ t('models.runtimeRole') }}: {{ model.role || '-' }}</span>
                <span>{{ t('models.contextWindow') }}: {{ model.context_window || '-' }}</span>
                <span>{{ t('models.maxOutput') }}: {{ model.max_output || '-' }}</span>
                <span>{{ t('models.reasoningEffort') }}: {{ model.reasoning_effort || '-' }}</span>
              </div>
              <div class="runtime-price">{{ priceText(model.pricing) }}</div>
            </article>
          </div>
        </section>
        <ProvidersPanel @edit="openEditModal" />
      </NSpin>
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

.runtime-panel {
  background: $bg-card;
  border: 1px solid $border-color;
  border-radius: $radius-md;
  padding: 16px;
  margin-bottom: 16px;
}

.runtime-panel__header,
.runtime-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.runtime-panel__header {
  margin-bottom: 14px;

  h3 {
    font-size: 15px;
    font-weight: 600;
  }

  p {
    color: $text-muted;
    font-size: 12px;
  }
}

.runtime-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 360px), 1fr));
  gap: 12px;
}

.runtime-card {
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  padding: 12px;
}

.runtime-card__head {
  strong {
    display: block;
    font-size: 13px;
    font-weight: 600;
  }

  span {
    display: block;
    color: $text-muted;
    font-size: 12px;
  }
}

.runtime-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 10px;
  margin-top: 12px;
  color: $text-secondary;
  font-size: 12px;
}

.runtime-price {
  margin-top: 10px;
  color: $text-muted;
  font-size: 12px;
}
</style>
