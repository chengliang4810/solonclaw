<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Button, Spin, Empty, Select, message } from 'antdv-next'
import { useModelsStore } from '@/stores/solonclaw/models'
import { emptyTaskModelRoutes, type TaskModelCategory, type TaskModelRoutes } from '@/api/solonclaw/system'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const modelsStore = useModelsStore()

const savingKey = ref<string | null>(null)
const defaultProvider = ref('')
const defaultModel = ref('')
const fallbackRows = ref<Array<{ provider: string; model: string }>>([])
const taskRoutes = ref<TaskModelRoutes>(emptyTaskModelRoutes())

const taskCategories: TaskModelCategory[] = [
  'monitor',
  'background_review',
  'curator',
  'approval',
  'compression',
  'cron',
]

const providerOptions = computed(() => {
  const options = modelsStore.providers.map(provider => ({
    label: provider.label,
    value: provider.provider,
    disabled: false,
  }))
  const registered = new Set(options.map(option => option.value))
  const currentProviders = [
    defaultProvider.value,
    ...fallbackRows.value.map(row => row.provider),
    ...taskCategories.map(category => taskRoutes.value[category].provider),
  ]
  for (const provider of currentProviders) {
    if (provider && !registered.has(provider)) {
      registered.add(provider)
      options.push({
        label: t('models.unregisteredProvider', { provider }),
        value: provider,
        disabled: true,
      })
    }
  }
  return options
})

/** 同步主模型 Provider 草稿，不覆盖未保存的模型选择。 */
function syncDefaultProviderForm() {
  defaultProvider.value = modelsStore.defaultProvider
}

/** 同步主模型名草稿，不覆盖未保存的 Provider 选择。 */
function syncDefaultModelForm() {
  defaultModel.value = modelsStore.defaultModel
}

/** 同步故障切换表单，不覆盖其他仍在编辑的配置区。 */
function syncFallbackForm() {
  fallbackRows.value = modelsStore.fallbackProviders.map(item => ({
    provider: item.provider,
    model: item.model,
  }))
}

/** 同步后台任务路由表单，不覆盖其他仍在编辑的配置区。 */
function syncTaskRoutesForm() {
  taskRoutes.value = Object.fromEntries(
    taskCategories.map(category => [category, { ...modelsStore.taskModelRoutes[category] }]),
  ) as TaskModelRoutes
}

onMounted(async () => {
  if (modelsStore.providers.length === 0) {
    await modelsStore.fetchProviders()
  }
  syncDefaultProviderForm()
  syncDefaultModelForm()
  syncFallbackForm()
  syncTaskRoutesForm()
})

watch(
  () => modelsStore.defaultProvider,
  () => syncDefaultProviderForm(),
)

watch(
  () => modelsStore.defaultModel,
  () => syncDefaultModelForm(),
)

watch(
  () => modelsStore.fallbackProviders,
  () => syncFallbackForm(),
  { deep: true },
)

watch(
  () => modelsStore.taskModelRoutes,
  () => syncTaskRoutesForm(),
  { deep: true },
)

async function handleSaveDefault() {
  if (!defaultProvider.value) {
    message.warning(t('models.selectProviderRequired'))
    return
  }
  if (!defaultModel.value.trim()) {
    message.warning(t('models.modelRequired'))
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

/** 返回指定 Provider 的模型选项，并以禁用项保留历史未登记值。 */
function modelOptions(providerKey: string, currentModel = '') {
  const provider = modelsStore.providers.find(item => item.provider === providerKey)
  const models = Array.from(new Set(provider?.models || []))
  const options = models.map(model => ({ label: model, value: model, disabled: false }))
  if (currentModel && !models.includes(currentModel)) {
    options.push({
      label: t('models.unregisteredModel', { model: currentModel }),
      value: currentModel,
      disabled: true,
    })
  }
  return options
}

function providerDefaultModel(providerKey: string) {
  const provider = modelsStore.providers.find(item => item.provider === providerKey)
  return provider?.defaultModel || provider?.models[0] || ''
}

function handleDefaultProviderChange() {
  defaultModel.value = providerDefaultModel(defaultProvider.value)
}

function addFallbackRow() {
  fallbackRows.value.push({ provider: '', model: '' })
}

function removeFallbackRow(index: number) {
  fallbackRows.value.splice(index, 1)
}

function handleFallbackProviderChange(index: number) {
  const row = fallbackRows.value[index]
  if (row) row.model = ''
}

async function handleSaveFallbacks() {
  const cleaned = fallbackRows.value
    .filter(item => item.provider)
    .map(item => ({
      provider: item.provider,
      model: String(item.model || '').trim(),
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

function handleTaskProviderChange(category: TaskModelCategory) {
  const route = taskRoutes.value[category]
  route.provider = route.provider || ''
  route.model = route.provider ? providerDefaultModel(route.provider) : ''
}

async function handleSaveTaskRoutes() {
  const normalized = Object.fromEntries(taskCategories.map(category => {
    const route = taskRoutes.value[category]
    return [category, {
      provider: String(route.provider || '').trim(),
      model: String(route.model || '').trim(),
    }]
  })) as TaskModelRoutes

  const invalidCategory = taskCategories.find(category => {
    const route = normalized[category]
    return Boolean(route.provider) !== Boolean(route.model)
  })
  if (invalidCategory) {
    message.warning(t('models.providerModelPairRequired'))
    return
  }

  savingKey.value = 'task-routes'
  try {
    await modelsStore.saveTaskModelRoutes(normalized)
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
      <div v-if="modelsStore.providers.length === 0 && !modelsStore.loadError" class="empty-hint">
        <Empty :description="t('settings.models.noProviders')" />
      </div>

      <template v-if="modelsStore.providers.length > 0">
        <div class="panel">
          <div class="panel-header">
            <h4>{{ t('models.defaultProviderSection') }}</h4>
          </div>
          <div class="field-grid">
            <Select
              v-model:value="defaultProvider"
              :options="providerOptions"
              :placeholder="t('models.chooseProvider')"
              @change="handleDefaultProviderChange"
            />
            <Select
              v-model:value="defaultModel"
              :options="modelOptions(defaultProvider, defaultModel)"
              :placeholder="t('models.selectModel')"
              :disabled="!defaultProvider"
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
            <div>
              <h4>{{ t('models.taskRoutesTitle') }}</h4>
              <p>{{ t('models.taskRoutesHint') }}</p>
            </div>
          </div>
          <div class="task-routes">
            <div v-for="category in taskCategories" :key="category" class="task-route-row">
              <div class="task-route-label">
                <strong>{{ t(`models.taskRoutes.${category}.label`) }}</strong>
                <span>{{ t(`models.taskRoutes.${category}.hint`) }}</span>
              </div>
              <Select
                v-model:value="taskRoutes[category].provider"
                :options="providerOptions"
                :placeholder="t('models.inheritMainModel')"
                allow-clear
                @change="handleTaskProviderChange(category)"
              />
              <Select
                v-model:value="taskRoutes[category].model"
                :options="modelOptions(taskRoutes[category].provider, taskRoutes[category].model)"
                :placeholder="t('models.selectModel')"
                :disabled="!taskRoutes[category].provider"
              />
            </div>
          </div>
          <div class="actions">
            <Button
              type="primary"
              :loading="savingKey === 'task-routes'"
              @click="handleSaveTaskRoutes"
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
              @change="handleFallbackProviderChange(index)"
            />
            <Select
              v-model:value="row.model"
              :options="modelOptions(row.provider, row.model)"
              :placeholder="t('models.optionalModelOverride')"
              :disabled="!row.provider"
              allow-clear
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

  p {
    margin: 4px 0 0;
    color: $text-muted;
    font-size: 12px;
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

.task-routes {
  display: grid;
  gap: 10px;
}

.task-route-row {
  display: grid;
  grid-template-columns: minmax(180px, 1.2fr) minmax(0, 1fr) minmax(0, 1fr);
  align-items: center;
  gap: 10px;
}

.task-route-label {
  display: grid;
  gap: 2px;

  strong {
    color: $text-primary;
    font-size: 13px;
  }

  span {
    color: $text-muted;
    font-size: 12px;
  }
}

.actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 760px) {
  .field-grid,
  .fallback-row,
  .task-route-row {
    grid-template-columns: 1fr;
  }
}
</style>
