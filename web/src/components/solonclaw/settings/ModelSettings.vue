<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Input, Button, Spin, Empty, Select, message } from 'antdv-next'
import { useModelsStore } from '@/stores/solonclaw/models'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const modelsStore = useModelsStore()

const savingKey = ref<string | null>(null)
const defaultProvider = ref('')
const defaultModel = ref('')
const fallbackRows = ref<Array<{ provider: string; model: string }>>([])

const providerOptions = computed(() =>
  modelsStore.providers.map(provider => ({
    label: provider.label,
    value: provider.provider,
  })),
)

function syncForms() {
  defaultProvider.value = modelsStore.defaultProvider
  defaultModel.value = modelsStore.defaultModel
  fallbackRows.value = modelsStore.fallbackProviders.map(item => ({
    provider: item.provider,
    model: item.model,
  }))
}

onMounted(async () => {
  if (modelsStore.providers.length === 0) {
    await modelsStore.fetchProviders()
  }
  syncForms()
})

watch(
  () => [modelsStore.providers, modelsStore.defaultProvider, modelsStore.defaultModel, modelsStore.fallbackProviders],
  () => syncForms(),
  { deep: true },
)

async function handleSaveDefault() {
  if (!defaultProvider.value) {
    message.warning(t('models.selectProviderRequired'))
    return
  }
  savingKey.value = 'default'
  try {
    await modelsStore.setDefaultModel(defaultModel.value.trim(), defaultProvider.value)
    message.success(t('settings.models.saved'))
  } catch (e: any) {
    message.error(e.message || t('settings.models.saveFailed'))
  } finally {
    savingKey.value = null
  }
}

function addFallbackRow() {
  fallbackRows.value.push({ provider: '', model: '' })
}

function removeFallbackRow(index: number) {
  fallbackRows.value.splice(index, 1)
}

async function handleSaveFallbacks() {
  const cleaned = fallbackRows.value
    .filter(item => item.provider)
    .map(item => ({
      provider: item.provider,
      model: item.model.trim(),
    }))

  savingKey.value = 'fallbacks'
  try {
    await modelsStore.saveFallbackProviders(cleaned)
    message.success(t('settings.models.saved'))
  } catch (e: any) {
    message.error(e.message || t('settings.models.saveFailed'))
  } finally {
    savingKey.value = null
  }
}
</script>

<template>
  <section class="settings-section">
    <Spin :spinning="modelsStore.loading">
      <div v-if="modelsStore.loadError" class="providers-load-error">
        <strong>{{ t('models.fetchFailed') }}</strong>
        <span>{{ modelsStore.loadError }}</span>
      </div>
      <div v-else-if="modelsStore.providers.length === 0" class="empty-hint">
        <Empty :description="t('settings.models.noProviders')" />
      </div>

      <template v-else>
        <div class="panel">
          <div class="panel-header">
            <h4>{{ t('models.defaultProviderSection') }}</h4>
          </div>
          <div class="field-grid">
            <Select
              v-model:value="defaultProvider"
              :options="providerOptions"
              :placeholder="t('models.chooseProvider')"
            />
            <Input
              v-model:value="defaultModel"
              :placeholder="t('models.defaultModel')"
            />
            <Button
              type="primary"
              :loading="savingKey === 'default'"
              @click="handleSaveDefault"
            >
              {{ t('settings.models.save') }}
            </Button>
          </div>
        </div>

        <div class="panel">
          <div class="panel-header">
            <h4>{{ t('models.fallbackProviders') }}</h4>
            <Button size="small" type="default" @click="addFallbackRow">{{ t('common.add') }}</Button>
          </div>
          <div v-if="fallbackRows.length === 0" class="empty-inline">
            {{ t('models.noFallbackProviders') }}
          </div>
          <div v-for="(row, index) in fallbackRows" :key="index" class="fallback-row">
            <Select
              v-model:value="row.provider"
              :options="providerOptions"
              :placeholder="t('models.chooseProvider')"
            />
            <Input
              v-model:value="row.model"
              :placeholder="t('models.optionalModelOverride')"
            />
            <Button type="text" danger @click="removeFallbackRow(index)">
              {{ t('common.delete') }}
            </Button>
          </div>
          <div class="actions">
            <Button
              type="primary"
              :loading="savingKey === 'fallbacks'"
              @click="handleSaveFallbacks"
            >
              {{ t('settings.models.save') }}
            </Button>
          </div>
        </div>
      </template>
    </Spin>
  </section>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.settings-section {
  margin-top: 16px;
}

.empty-hint {
  padding: 40px 0;
}

.providers-load-error {
  display: grid;
  gap: 6px;
  padding: 14px;
  border: 1px solid rgba(var(--error-rgb), 0.28);
  border-radius: $radius-sm;
  color: $error;
  background: rgba(var(--error-rgb), 0.06);

  span {
    overflow-wrap: anywhere;
  }
}

.panel {
  border: 1px solid $border-color;
  border-radius: $radius-md;
  padding: 16px;
  margin-bottom: 16px;
  background: $bg-card;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;

  h4 {
    margin: 0;
    font-size: 14px;
    font-weight: 600;
  }
}

.field-grid,
.fallback-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.fallback-row + .fallback-row {
  margin-top: 10px;
}

.empty-inline {
  font-size: 13px;
  color: $text-muted;
}

.actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
