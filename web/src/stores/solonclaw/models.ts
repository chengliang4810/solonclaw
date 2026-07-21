import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as systemApi from '@/api/solonclaw/system'
import type {
  AvailableModelGroup,
  CustomProvider,
  DialectCatalogItem,
  FallbackProvider,
  ProviderValidationRequest,
  ProviderValidationResponse,
  ModelsHealthProvider,
  RuntimeModelStatus,
  TaskModelRoutes,
} from '@/api/solonclaw/system'
import { LLM_DIALECT_OPTIONS, normalizeDialectCatalog } from '@/shared/providerDisplay'
import { useAppStore } from './app'
import { useProfileContextGuard } from '@/composables/useProfileContextGuard'

export const useModelsStore = defineStore('models', () => {
  const providers = ref<AvailableModelGroup[]>([])
  const allProviders = ref<AvailableModelGroup[]>([])
  const fallbackProviders = ref<FallbackProvider[]>([])
  const defaultModel = ref('')
  const defaultProvider = ref('')
  const taskModelRoutes = ref<TaskModelRoutes>(systemApi.emptyTaskModelRoutes())
  const loading = ref(false)
  const loadError = ref<string | null>(null)
  const providerHealth = ref<Record<string, ModelsHealthProvider>>({})
  const runtimeModels = ref<RuntimeModelStatus[]>([])
  const dialectCatalog = ref<DialectCatalogItem[]>([...LLM_DIALECT_OPTIONS])

  /** 清空当前 Profile 的模型管理状态。 */
  function resetProfileState(): void {
    providers.value = []
    allProviders.value = []
    fallbackProviders.value = []
    defaultModel.value = ''
    defaultProvider.value = ''
    taskModelRoutes.value = systemApi.emptyTaskModelRoutes()
    loading.value = false
    loadError.value = null
    providerHealth.value = {}
    runtimeModels.value = []
    dialectCatalog.value = [...LLM_DIALECT_OPTIONS]
  }

  const profileContext = useProfileContextGuard(resetProfileState)

  const allModels = computed(() =>
    providers.value.flatMap(g =>
      g.models.map(m => ({
        id: m,
        provider: g.provider,
        label: g.label,
        base_url: g.base_url,
        dialect: g.dialect,
        isDefault: g.provider === defaultProvider.value && m === defaultModel.value,
      })),
    ),
  )

  async function fetchProviders() {
    const contextVersion = profileContext.capture()
    loading.value = true
    loadError.value = null
    try {
      const res = await systemApi.fetchAvailableModels()
      if (!profileContext.isCurrent(contextVersion)) return
      providers.value = res.groups
      allProviders.value = res.allProviders
      defaultModel.value = res.default
      defaultProvider.value = res.default_provider
      fallbackProviders.value = res.fallbackProviders
      taskModelRoutes.value = res.taskModelRoutes
      dialectCatalog.value = normalizeDialectCatalog(res.dialectCatalog)
    } catch (err) {
      if (profileContext.isCurrent(contextVersion)) {
        console.error('Failed to fetch providers:', err)
        loadError.value = err instanceof Error ? err.message : String(err || 'Failed to fetch providers')
      }
    } finally {
      if (profileContext.isCurrent(contextVersion)) loading.value = false
    }
  }

  async function setDefaultModel(modelId: string, provider: string) {
    const contextVersion = profileContext.capture()
    await systemApi.updateDefaultModel({ default: modelId, provider })
    if (!profileContext.isCurrent(contextVersion)) return
    defaultModel.value = modelId
    defaultProvider.value = provider
    providers.value = providers.value.map(item => ({
      ...item,
      isDefault: item.provider === provider,
    }))
    allProviders.value = allProviders.value.map(item => ({
      ...item,
      isDefault: item.provider === provider,
    }))
    const appStore = useAppStore()
    appStore.loadModels()
  }

  async function addProvider(data: CustomProvider) {
    const contextVersion = profileContext.capture()
    await systemApi.addCustomProvider(data)
    if (!profileContext.isCurrent(contextVersion)) return
    await fetchProviders()
    const appStore = useAppStore()
    appStore.loadModels()
  }

  async function fetchProviderModels(data: {
    providerKey?: string
    baseUrl: string
    apiKey?: string
    dialect: string
    model?: string
    defaultModel?: string
  }) {
    return systemApi.fetchProviderModels(data)
  }

  async function fetchModelsHealth() {
    const contextVersion = profileContext.capture()
    const res = await systemApi.fetchModelsHealth()
    if (!profileContext.isCurrent(contextVersion)) return
    providerHealth.value = Object.fromEntries(res.providers.map(item => [item.provider, item]))
  }

  async function fetchRuntimeModels() {
    const contextVersion = profileContext.capture()
    const res = await systemApi.fetchRuntimeModels()
    if (!profileContext.isCurrent(contextVersion)) return
    runtimeModels.value = res.models || []
  }

  async function validateProvider(data: ProviderValidationRequest): Promise<ProviderValidationResponse> {
    return systemApi.validateProvider(data)
  }

  async function updateProvider(providerKey: string, data: {
    name?: string
    baseUrl?: string
    apiKey?: string
    defaultModel?: string
    models?: string[]
    dialect?: string
  }) {
    const contextVersion = profileContext.capture()
    await systemApi.updateProvider(providerKey, data)
    if (!profileContext.isCurrent(contextVersion)) return
    await fetchProviders()
    const appStore = useAppStore()
    appStore.loadModels()
  }

  async function fetchTaskModelRoutes() {
    const contextVersion = profileContext.capture()
    const routes = await systemApi.fetchTaskModelRoutes()
    if (!profileContext.isCurrent(contextVersion)) return
    taskModelRoutes.value = routes
  }

  async function saveTaskModelRoutes(routes: TaskModelRoutes) {
    const contextVersion = profileContext.capture()
    const saved = await systemApi.updateTaskModelRoutes(routes)
    if (!profileContext.isCurrent(contextVersion)) return
    taskModelRoutes.value = saved
  }

  async function saveFallbackProviders(next: FallbackProvider[]) {
    const contextVersion = profileContext.capture()
    await systemApi.updateFallbackProviders(next)
    if (!profileContext.isCurrent(contextVersion)) return
    fallbackProviders.value = next
  }

  async function removeProvider(providerKey: string) {
    const contextVersion = profileContext.capture()
    await systemApi.removeCustomProvider(providerKey)
    if (!profileContext.isCurrent(contextVersion)) return
    await fetchProviders()
    const appStore = useAppStore()
    appStore.loadModels()
  }

  return {
    providers,
    allProviders,
    fallbackProviders,
    defaultModel,
    defaultProvider,
    taskModelRoutes,
    loading,
    loadError,
    providerHealth,
    runtimeModels,
    dialectCatalog,
    allModels,
    fetchProviders,
    setDefaultModel,
    addProvider,
    fetchProviderModels,
    fetchModelsHealth,
    fetchRuntimeModels,
    validateProvider,
    updateProvider,
    fetchTaskModelRoutes,
    saveTaskModelRoutes,
    saveFallbackProviders,
    removeProvider,
  }
})
