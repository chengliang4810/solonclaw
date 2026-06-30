<script setup lang="ts">
import { computed, ref } from 'vue'
import { Modal, Form, FormItem, Input, Button, Select, AutoComplete, message } from 'antdv-next'
import { useModelsStore } from '@/stores/solonclaw/models'
import type { AvailableModelGroup } from '@/api/solonclaw/system'
import { PROVIDER_FORM_FIELD_LABEL_KEYS, baseUrlPlaceholderForDialect, translateDialectOptions } from '@/shared/providerDisplay'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = defineProps<{
  provider?: AvailableModelGroup | null
}>()

const emit = defineEmits<{
  close: []
  saved: []
}>()

const modelsStore = useModelsStore()

const showModal = ref(true)
const loading = ref(false)
const modelsLoading = ref(false)
const modelOptions = ref<Array<{ label: string; value: string }>>([])
const isEdit = computed(() => !!props.provider)
const formData = ref({
  providerKey: props.provider?.provider || '',
  name: props.provider?.label || '',
  baseUrl: props.provider?.base_url || '',
  apiKey: '',
  defaultModel: props.provider?.models?.[0] || '',
  dialect: props.provider?.dialect || 'openai-responses',
})

function baseUrlPlaceholder(): string {
  return baseUrlPlaceholderForDialect(formData.value.dialect)
}

function errorMessage(error: unknown, fallback?: string): string {
  if (error instanceof Error) return error.message
  return fallback ?? String(error)
}

const dialectOptions = computed(() => translateDialectOptions(t))

async function handleSave() {
  if (!formData.value.providerKey.trim()) {
    message.warning(t('models.providerKeyRequired'))
    return
  }
  if (!formData.value.name.trim()) {
    message.warning(t('models.nameRequired'))
    return
  }
  if (!formData.value.baseUrl.trim()) {
    message.warning(t('models.baseUrlRequired'))
    return
  }
  if (!formData.value.defaultModel.trim()) {
    message.warning(t('models.modelRequired'))
    return
  }

  loading.value = true
  try {
    if (isEdit.value) {
      const nextProvider = {
        name: formData.value.name.trim(),
        baseUrl: formData.value.baseUrl.trim(),
        defaultModel: formData.value.defaultModel.trim(),
        dialect: formData.value.dialect,
        apiKey: formData.value.apiKey.trim() || undefined,
      }
      await modelsStore.updateProvider(formData.value.providerKey.trim(), {
        ...nextProvider,
      })
      message.success(t('models.providerUpdated'))
    } else {
      await modelsStore.addProvider({
        providerKey: formData.value.providerKey.trim(),
        name: formData.value.name.trim(),
        baseUrl: formData.value.baseUrl.trim(),
        apiKey: formData.value.apiKey.trim(),
        defaultModel: formData.value.defaultModel.trim(),
        dialect: formData.value.dialect,
      })
      message.success(t('models.providerAdded'))
    }
    emit('saved')
  } catch (error: unknown) {
    message.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function fetchModelList() {
  if (!formData.value.baseUrl.trim()) {
    message.warning(t('models.baseUrlRequired'))
    return
  }
  modelsLoading.value = true
  try {
    const res = await modelsStore.fetchProviderModels({
      providerKey: isEdit.value ? formData.value.providerKey.trim() : undefined,
      baseUrl: formData.value.baseUrl.trim(),
      apiKey: formData.value.apiKey.trim(),
      dialect: formData.value.dialect,
      model: formData.value.defaultModel.trim(),
      defaultModel: formData.value.defaultModel.trim(),
    })
    modelOptions.value = (res.models || []).map(model => ({ label: model, value: model }))
    if (!modelOptions.value.length) {
      message.warning(t('models.noRemoteModels'))
      return
    }
    if (!formData.value.defaultModel.trim()) {
      formData.value.defaultModel = modelOptions.value[0].value
    }
    message.success(t('models.modelsFetched'))
  } catch (error: unknown) {
    message.error(errorMessage(error, t('models.fetchModelsFailed')))
  } finally {
    modelsLoading.value = false
  }
}

function handleClose() {
  showModal.value = false
  setTimeout(() => emit('close'), 200)
}
</script>

<template>
  <Modal
    v-model:open="showModal"

    :title="isEdit ? t('models.editProvider') : t('models.addProvider')"
    :style="{ width: 'min(560px, calc(100vw - 32px))' }"
    :mask-closable="!loading"
    @after-leave="emit('close')"
  >
    <Form layout="vertical">
      <FormItem :label="t(PROVIDER_FORM_FIELD_LABEL_KEYS.providerKey)" required>
        <Input
          v-model:value="formData.providerKey"
          :placeholder="t('models.providerKeyPlaceholder')"
          :disabled="isEdit"
        />
      </FormItem>

      <FormItem :label="t(PROVIDER_FORM_FIELD_LABEL_KEYS.name)" required>
        <Input
          v-model:value="formData.name"
          :placeholder="t('models.namePlaceholder')"
        />
      </FormItem>

      <FormItem :label="t(PROVIDER_FORM_FIELD_LABEL_KEYS.baseUrl)" required>
        <Input
          v-model:value="formData.baseUrl"
          :placeholder="baseUrlPlaceholder()"
        />
      </FormItem>

      <FormItem :label="t(PROVIDER_FORM_FIELD_LABEL_KEYS.apiKey)">
        <Input
          v-model:value="formData.apiKey"
          type="password"

          :placeholder="isEdit && provider?.has_api_key ? t('models.apiKeyConfigured') : t('models.apiKeyPlaceholder')"
          autocomplete="off"
        />
      </FormItem>

      <FormItem :label="t(PROVIDER_FORM_FIELD_LABEL_KEYS.defaultModel)" required>
        <div class="model-select-row">
          <AutoComplete
            v-model:value="formData.defaultModel"
            :options="modelOptions"
            :placeholder="t('models.selectOrInput')"
          />
          <Button :loading="modelsLoading" @click="fetchModelList">
            {{ t('models.fetchModelList') }}
          </Button>
        </div>
      </FormItem>

      <FormItem :label="t(PROVIDER_FORM_FIELD_LABEL_KEYS.dialect)" required>
        <div class="dialect-field">
          <Select
            v-model:value="formData.dialect"
            :options="dialectOptions"
          />
          <p class="field-hint">{{ t('models.dialectHint') }}</p>
        </div>
      </FormItem>
    </Form>

    <template #footer>
      <div class="modal-footer">
        <Button @click="handleClose">{{ t('common.cancel') }}</Button>
        <Button type="primary" :loading="loading" @click="handleSave">
          {{ isEdit ? t('common.save') : t('common.add') }}
        </Button>
      </div>
    </template>
  </Modal>
</template>

<style scoped lang="scss">
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.model-select-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px;
}

.dialect-field {
  width: 100%;
}

.field-hint {
  margin: 8px 0 0;
  color: var(--text-color-3);
  font-size: 12px;
  line-height: 1.5;
}
</style>
