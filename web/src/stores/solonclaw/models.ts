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
} from '@/api/solonclaw/system'
import { LLM_DIALECT_OPTIONS, normalizeDialectCatalog } from '@/shared/providerDisplay'
import { useAppStore } from './app'

export const useModelsStore = defineStore('models', () => {
  const providers = ref<AvailableModelGroup[]>([])
  const allProviders = ref<AvailableModelGroup[]>([])
  const fallbackProviders = ref<FallbackProvider[]>([])
  const defaultModel = ref('')
  const defaultProvider = ref('')
  const loading = ref(false)
  const loadError = ref<string | null>(null)
  const providerHealth = ref<Record<string, ModelsHealthProvider>>({})
  const runtimeModels = ref<RuntimeModelStatus[]>([])
  const dialectCatalog = ref<DialectCatalogItem[]>([...LLM_DIALECT_OPTIONS])

  const allModels = computed(() =>
    providers.value.flatMap(g =>
      g.models.map(m => ({
        id: m,
        provider: g.provider,
        label: g.label,
        base_url: g.base_url,
        dialect: g.dialect,
        isDefault: m === defaultModel.value,
      })),
    ),
  )

  async function fetchProviders() {
    loading.value = true
    loadError.value = null
    try {
      const res = await systemApi.fetchAvailableModels()
      providers.value = res.groups
      allProviders.value = res.allProviders
      defaultModel.value = res.default
      defaultProvider.value = res.default_provider
      fallbackProviders.value = res.fallbackProviders
      dialectCatalog.value = normalizeDialectCatalog(res.dialectCatalog)
    } catch (err) {
      console.error('Failed to fetch providers:', err)
      providers.value = []
      allProviders.value = []
      fallbackProviders.value = []
      defaultModel.value = ''
      defaultProvider.value = ''
      dialectCatalog.value = [...LLM_DIALECT_OPTIONS]
      loadError.value = err instanceof Error ? err.message : String(err || 'Failed to fetch providers')
    } finally {
      loading.value = false
    }
  }

  async function setDefaultModel(modelId: string, provider: string) {
    await systemApi.updateDefaultModel({ default: modelId, provider })
    defaultModel.value = modelId
    const appStore = useAppStore()
    appStore.loadModels()
  }

  async function addProvider(data: CustomProvider) {
    await systemApi.addCustomProvider(data)
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
    const res = await systemApi.fetchModelsHealth()
    providerHealth.value = Object.fromEntries(res.providers.map(item => [item.provider, item]))
  }

  async function fetchRuntimeModels() {
    const res = await systemApi.fetchRuntimeModels()
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
    dialect?: string
  }) {
    await systemApi.updateProvider(providerKey, data)
    await fetchProviders()
    const appStore = useAppStore()
    appStore.loadModels()
  }

  async function saveFallbackProviders(next: FallbackProvider[]) {
    await systemApi.updateFallbackProviders(next)
    fallbackProviders.value = next
    await fetchProviders()
  }

  async function removeProvider(providerKey: string) {
    await systemApi.removeCustomProvider(providerKey)
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
    saveFallbackProviders,
    removeProvider,
  }
})
