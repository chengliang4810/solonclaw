<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Modal, Form, FormItem, Input, Button, Select, message } from 'antdv-next'
import { useModelsStore } from '@/stores/solonclaw/models'
import type { AvailableModelGroup } from '@/api/solonclaw/system'
import {
  PROVIDER_FORM_FIELD_LABEL_KEYS,
  baseUrlPlaceholderForDialect,
  translateDialectCatalogOptions,
} from '@/shared/providerDisplay'
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
const discoveredModels = ref<string[]>([])
/** 用户手工输入或编辑前已经保存的模型；连接参数变化时必须保留。 */
const manualModels = ref<string[]>([...(props.provider?.models || [])])
const isEdit = computed(() => !!props.provider)
let automaticFetchTimer: ReturnType<typeof setTimeout> | undefined
let modelRequestId = 0
let activeModelRequest: { id: number; signature: string } | null = null
const formData = ref({
  providerKey: props.provider?.provider || '',
  name: props.provider?.label || '',
  baseUrl: props.provider?.base_url || '',
  apiKey: '',
  defaultModel: props.provider?.defaultModel || props.provider?.models?.[0] || '',
  models: [...(props.provider?.models || [])],
  dialect: props.provider?.dialect || 'openai-responses',
})

const modelOptions = computed(() =>
  Array.from(new Set([...formData.value.models, ...discoveredModels.value].map(model => model.trim()).filter(Boolean)))
    .map(model => ({ label: model, value: model })),
)

function baseUrlPlaceholder(): string {
  return baseUrlPlaceholderForDialect(formData.value.dialect)
}

function errorMessage(error: unknown, fallback?: string): string {
  if (error instanceof Error) return error.message
  return fallback ?? String(error)
}

const dialectOptions = computed(() => translateDialectCatalogOptions(t, modelsStore.dialectCatalog))

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

  const models = Array.from(new Set([
    formData.value.defaultModel.trim(),
    ...formData.value.models.map(model => model.trim()),
  ].filter(Boolean)))

  loading.value = true
  try {
    if (isEdit.value) {
      const nextProvider = {
        name: formData.value.name.trim(),
        baseUrl: formData.value.baseUrl.trim(),
        defaultModel: formData.value.defaultModel.trim(),
        models,
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
        models,
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

/** 生成模型发现请求的稳定签名，用于合并相同连接参数的并发请求。 */
function modelRequestSignature(): string {
  return JSON.stringify([
    formData.value.baseUrl.trim(),
    formData.value.apiKey.trim(),
    formData.value.dialect,
  ])
}

/** 拉取远端模型；自动模式保持静默，手动模式显示明确反馈。 */
async function fetchModelList(silent = false) {
  if (!formData.value.baseUrl.trim()) {
    if (!silent) message.warning(t('models.baseUrlRequired'))
    return
  }

  const signature = modelRequestSignature()
  if (activeModelRequest?.signature === signature && activeModelRequest.id === modelRequestId) return
  const requestId = ++modelRequestId
  activeModelRequest = { id: requestId, signature }
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
    if (requestId !== modelRequestId) return
    discoveredModels.value = Array.from(new Set(res.models || []))
    if (!discoveredModels.value.length) {
      if (!silent) message.warning(t('models.noRemoteModels'))
      return
    }
    formData.value.models = Array.from(new Set([
      ...manualModels.value,
      ...discoveredModels.value,
    ]))
    if (!formData.value.defaultModel.trim()) {
      formData.value.defaultModel = discoveredModels.value[0]
    }
    if (!silent) message.success(t('models.modelsFetched'))
  } catch (error: unknown) {
    if (requestId === modelRequestId && !silent) {
      message.error(errorMessage(error, t('models.fetchModelsFailed')))
    }
  } finally {
    if (requestId === modelRequestId) modelsLoading.value = false
    if (activeModelRequest?.id === requestId) activeModelRequest = null
  }
}

/** 新增 Provider 时，在连接参数稳定后自动发现模型。 */
function scheduleAutomaticModelFetch() {
  if (isEdit.value) return
  const previousDiscoveredModels = new Set(discoveredModels.value)
  const currentDefaultModel = formData.value.defaultModel.trim()
  if (
    currentDefaultModel
    && previousDiscoveredModels.has(currentDefaultModel)
    && !manualModels.value.includes(currentDefaultModel)
  ) {
    formData.value.defaultModel = ''
  }
  modelRequestId += 1
  discoveredModels.value = []
  formData.value.models = [...manualModels.value]
  if (automaticFetchTimer) clearTimeout(automaticFetchTimer)
  if (!formData.value.baseUrl.trim()) {
    modelsLoading.value = false
    return
  }
  automaticFetchTimer = setTimeout(() => {
    automaticFetchTimer = undefined
    void fetchModelList(true)
  }, 500)
}

watch(
  () => [formData.value.baseUrl, formData.value.apiKey, formData.value.dialect],
  () => scheduleAutomaticModelFetch(),
)

onBeforeUnmount(() => {
  if (automaticFetchTimer) clearTimeout(automaticFetchTimer)
  modelRequestId += 1
})

function handleModelsChange() {
  const discovered = new Set(discoveredModels.value)
  manualModels.value = formData.value.models.filter(model => !discovered.has(model))
  if (formData.value.defaultModel && !formData.value.models.includes(formData.value.defaultModel)) {
    formData.value.defaultModel = formData.value.models[0] || ''
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

      <FormItem :label="t('models.configuredModels')" required>
        <div class="model-select-row">
          <Select
            v-model:value="formData.models"
            mode="tags"
            :options="modelOptions"
            :placeholder="t('models.modelsPlaceholder')"
            :token-separators="[',']"
            @change="handleModelsChange"
          />
          <Button :loading="modelsLoading" @click="fetchModelList(false)">
            {{ t('models.fetchModelList') }}
          </Button>
        </div>
      </FormItem>

      <FormItem :label="t(PROVIDER_FORM_FIELD_LABEL_KEYS.defaultModel)" required>
        <Select
          v-model:value="formData.defaultModel"
          :options="modelOptions"
          :placeholder="t('models.selectModel')"
        />
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
