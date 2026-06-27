<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Button, Spin } from 'antdv-next'
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

onMounted(async () => {
  await modelsStore.fetchProviders()
  modelsStore.fetchModelsHealth().catch(() => undefined)
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
</style>
