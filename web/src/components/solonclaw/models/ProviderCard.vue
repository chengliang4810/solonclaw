<script setup lang="ts">
import { computed, ref } from 'vue'
import { Button, message, Modal } from 'antdv-next'
import type { AvailableModelGroup, ProviderValidationResponse } from '@/api/solonclaw/system'
import { useModelsStore } from '@/stores/solonclaw/models'
import type { ProviderCardFieldKey } from '@/shared/providerDisplay'
import { PROVIDER_CARD_FIELD_ROWS, apiKeyStatusLabelKey, healthLabelKey, translateDialectLabel } from '@/shared/providerDisplay'
import { useI18n } from 'vue-i18n'

const props = defineProps<{ provider: AvailableModelGroup }>()
const emit = defineEmits<{
  edit: [provider: AvailableModelGroup]
}>()

const { t } = useI18n()
const modelsStore = useModelsStore()

const displayName = computed(() => props.provider.label)
const healthStatus = computed(() => modelsStore.providerHealth[props.provider.provider]?.status || 'unchecked')
const deleting = ref(false)
const validating = ref(false)
const validationResult = ref<ProviderValidationResponse | null>(null)

function errorMessage(error: unknown, fallback?: string): string {
  if (error instanceof Error) return error.message
  return fallback ?? String(error)
}

function providerFieldValue(key: ProviderCardFieldKey): string {
  switch (key) {
    case 'providerKey':
      return props.provider.provider
    case 'baseUrl':
      return props.provider.base_url
    case 'defaultModel':
      return props.provider.defaultModel || props.provider.models[0] || '—'
    case 'apiKey':
      return t(apiKeyStatusLabelKey(props.provider.has_api_key))
    case 'healthStatus':
      return t(healthLabelKey(healthStatus.value))
  }
}

async function handleDelete() {
  Modal.confirm({
    title: t('models.deleteProvider'),
    content: t('models.deleteConfirm', { name: displayName.value }),
    okText: t('common.delete'),
    cancelText: t('common.cancel'),
    onOk: async () => {
      deleting.value = true
      try {
        await modelsStore.removeProvider(props.provider.provider)
        message.success(t('models.providerDeleted'))
      } catch (error: unknown) {
        message.error(errorMessage(error))
      } finally {
        deleting.value = false
      }
    },
  })
}

async function handleValidate() {
  validating.value = true
  validationResult.value = null
  try {
    validationResult.value = await modelsStore.validateProvider({
      providerKey: props.provider.providerKey || props.provider.provider,
      baseUrl: props.provider.base_url,
      dialect: props.provider.dialect,
      model: props.provider.defaultModel || props.provider.models?.[0] || '',
      defaultModel: props.provider.defaultModel || props.provider.models?.[0] || '',
    })
    if (validationResult.value.ok) {
      message.success(t('models.providerValid'))
    } else {
      message.warning(validationResult.value.message || t('models.providerInvalid'))
    }
  } catch (error: unknown) {
    message.error(errorMessage(error, t('models.providerValidationFailed')))
  } finally {
    validating.value = false
  }
}
</script>

<template>
  <div class="provider-card">
    <div class="card-header">
      <h3 class="provider-name">{{ displayName }}</h3>
      <span class="type-badge" :class="provider.isDefault ? 'default' : 'normal'">
        {{ provider.isDefault ? t('models.defaultBadge') : translateDialectLabel(provider.dialect, t) }}
      </span>
    </div>

    <div class="card-body">
      <div v-for="row in PROVIDER_CARD_FIELD_ROWS" :key="row.key" class="info-row">
        <span class="info-label">{{ t(row.labelKey) }}</span>
        <code v-if="row.monospaced" class="info-value mono">{{ providerFieldValue(row.key) }}</code>
        <span v-else class="info-value">{{ providerFieldValue(row.key) }}</span>
      </div>
      <div class="model-list-summary">
        <span class="info-label">{{ t('models.configuredModels') }}</span>
        <span class="info-value">{{ t('models.modelCount', { count: provider.models.length }) }}</span>
      </div>
    </div>

    <div v-if="validationResult" class="validation-result" :class="validationResult.ok ? 'ok' : 'error'">
      <span>{{ t('models.validationStatus', { status: validationResult.status }) }}</span>
      <span class="validation-message">{{ validationResult.message }}</span>
    </div>

    <div class="card-actions">
      <Button size="small" type="text" :loading="validating" @click="handleValidate">{{ t('models.validateProvider') }}</Button>
      <Button size="small" type="text" @click="emit('edit', provider)">{{ t('common.edit') }}</Button>
      <Button size="small" type="text" danger :loading="deleting" @click="handleDelete">{{ t('common.delete') }}</Button>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.provider-card {
  background-color: $bg-card;
  border: 1px solid $border-color;
  border-radius: $radius-md;
  padding: 16px;
  transition: border-color $transition-fast;

  &:hover {
    border-color: rgba(var(--accent-primary-rgb), 0.3);
  }
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.provider-name {
  font-size: 15px;
  font-weight: 600;
  color: $text-primary;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 70%;
}

.type-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;

  &.default {
    background: rgba(var(--accent-primary-rgb), 0.12);
    color: $accent-primary;
  }

  &.normal {
    background: rgba(148, 163, 184, 0.12);
    color: $text-secondary;
  }
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 14px;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.model-list-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.info-label {
  font-size: 12px;
  color: $text-muted;
}

.info-value {
  font-size: 12px;
  color: $text-secondary;
}

.mono {
  font-family: $font-code;
  font-size: 12px;
}

.card-actions {
  display: flex;
  gap: 8px;
  border-top: 1px solid $border-light;
  padding-top: 10px;
}

.validation-result {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-bottom: 10px;
  padding: 8px;
  border-radius: $radius-sm;
  font-size: 12px;

  &.ok {
    background: rgba(34, 197, 94, 0.1);
    color: #16a34a;
  }

  &.error {
    background: rgba(239, 68, 68, 0.1);
    color: #dc2626;
  }
}

.validation-message {
  color: $text-muted;
  overflow-wrap: anywhere;
}
</style>
