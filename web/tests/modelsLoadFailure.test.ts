import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createPinia, setActivePinia } from 'pinia'
import { createSSRApp, defineComponent } from 'vue'
import { compileTemplate, parse } from 'vue/compiler-sfc'
import { renderToString } from 'vue/server-renderer'

const testsDir = dirname(fileURLToPath(import.meta.url))
const tempDir = mkdtempSync(join(testsDir, '.tmp-models-load-failure-'))
const originalConsoleError = console.error

try {
  const mockSystemApiPath = join(tempDir, 'mock-system-api.ts')
  const mockAppStorePath = join(tempDir, 'mock-app-store.ts')
  const mockProviderDisplayPath = join(tempDir, 'mock-provider-display.ts')
  const modelsStorePath = join(tempDir, 'models-store-under-test.ts')

  writeFileSync(mockSystemApiPath, `
export interface AvailableModelGroup {
  provider: string
  providerKey: string
  label: string
  base_url: string
  models: string[]
  dialect: string
  has_api_key: boolean
  isDefault: boolean
}
export type FallbackProvider = { provider: string; model: string }
export type ProviderValidationRequest = Record<string, unknown>
export type ProviderValidationResponse = Record<string, unknown>
export type ModelsHealthProvider = { provider: string; status: string; checked_at: number }
export type RuntimeModelStatus = Record<string, unknown>

type AvailableModelsResponse = {
  default: string
  default_provider: string
  groups: AvailableModelGroup[]
  allProviders: AvailableModelGroup[]
  fallbackProviders: FallbackProvider[]
}

const queuedResponses: Array<() => Promise<AvailableModelsResponse>> = []

export function queueProvidersSuccess(groups: AvailableModelGroup[]): void {
  queuedResponses.push(async () => ({
    default: groups[0]?.models[0] || '',
    default_provider: groups[0]?.provider || '',
    groups,
    allProviders: groups,
    fallbackProviders: [],
  }))
}

export function queueProvidersFailure(message: string): void {
  queuedResponses.push(async () => {
    throw new Error(message)
  })
}

export async function fetchAvailableModels(): Promise<AvailableModelsResponse> {
  const next = queuedResponses.shift()
  if (!next) throw new Error('No mock provider response queued')
  return next()
}

export async function updateDefaultModel(): Promise<void> {}
export async function addCustomProvider(): Promise<void> {}
export async function fetchProviderModels(): Promise<{ url: string; models: string[] }> { return { url: '', models: [] } }
export async function fetchModelsHealth(): Promise<{ providers: ModelsHealthProvider[] }> { return { providers: [] } }
export async function fetchRuntimeModels(): Promise<{ models: RuntimeModelStatus[] }> { return { models: [] } }
export async function validateProvider(): Promise<ProviderValidationResponse> { return {} }
export async function updateProvider(): Promise<void> {}
export async function updateFallbackProviders(): Promise<void> {}
export async function removeCustomProvider(): Promise<void> {}
`)
  writeFileSync(mockAppStorePath, `
export function useAppStore() {
  return { loadModels() {} }
}
`)
  writeFileSync(mockProviderDisplayPath, `
export const LLM_DIALECT_OPTIONS = [{ value: 'openai', label: 'OpenAI', labelKey: 'models.dialects.openai' }]
export function normalizeDialectCatalog(value) {
  return Array.isArray(value) && value.length ? value : LLM_DIALECT_OPTIONS
}
`)

  const storeSource = readFileSync(new URL('../src/stores/solonclaw/models.ts', import.meta.url), 'utf8')
    .replace("import * as systemApi from '@/api/solonclaw/system'", "import * as systemApi from './mock-system-api.ts'")
    .replace(/import type \{([\s\S]*?)\} from '@\/api\/solonclaw\/system'/, "import type {$1} from './mock-system-api.ts'")
    .replace("import { LLM_DIALECT_OPTIONS, normalizeDialectCatalog } from '@/shared/providerDisplay'", "import { LLM_DIALECT_OPTIONS, normalizeDialectCatalog } from './mock-provider-display.ts'")
    .replace("import { useAppStore } from './app'", "import { useAppStore } from './mock-app-store.ts'")
    .replace(
      "import { useProfileContextGuard } from '@/composables/useProfileContextGuard'",
      "function useProfileContextGuard() { return { capture: () => 0, isCurrent: () => true } }",
    )
  writeFileSync(modelsStorePath, storeSource)

  const mockSystemApi = await import(pathToFileURL(mockSystemApiPath).href)
  const { useModelsStore } = await import(pathToFileURL(modelsStorePath).href)

  setActivePinia(createPinia())
  const modelsStore = useModelsStore()
  const staleProvider = {
    provider: 'openai',
    providerKey: 'openai',
    label: 'OpenAI',
    base_url: 'https://api.example',
    models: ['mimo-v2.5'],
    dialect: 'openai',
    has_api_key: true,
    isDefault: true,
  }

  mockSystemApi.queueProvidersSuccess([staleProvider])
  await modelsStore.fetchProviders()
  assert.deepEqual(modelsStore.providers, [staleProvider], 'Given an initial load, providers should be present')
  assert.equal(modelsStore.loadError, null, 'A successful load should not keep an error')

  mockSystemApi.queueProvidersFailure('provider API unavailable')
  console.error = (...args: unknown[]) => {
    if (args[0] === 'Failed to fetch providers:') return
    originalConsoleError(...args)
  }
  await modelsStore.fetchProviders()
  console.error = originalConsoleError

  assert.deepEqual(modelsStore.providers, [staleProvider], 'When loading providers fails, stale providers should remain visible')
  assert.equal(modelsStore.loadError, 'provider API unavailable', 'When loading providers fails, the error should remain visible')
  assert.equal(modelsStore.loading, false, 'When loading providers fails, loading should be reset')

  const panelSource = readFileSync(new URL('../src/components/solonclaw/models/ProvidersPanel.vue', import.meta.url), 'utf8')
  const { descriptor } = parse(panelSource)
  const templateSource = descriptor.template?.content
  assert.ok(templateSource, 'ProvidersPanel should have a renderable template')

  const compiled = compileTemplate({
    id: 'models-load-failure-test',
    filename: 'ProvidersPanel.vue',
    source: templateSource,
    compilerOptions: { mode: 'function' },
  })
  assert.equal(compiled.errors.length, 0, 'ProvidersPanel template should compile for behavior verification')

  const render = new Function('Vue', compiled.code)(await import('vue'))
  const html = await renderToString(createSSRApp(defineComponent({
    components: {
      ProviderCard: { props: ['provider'], template: '<article>{{ provider.label }}</article>' },
    },
    setup() {
      return {
        modelsStore: {
          providers: [staleProvider],
          loadError: 'provider API unavailable',
        },
        t: (key: string) => key === 'models.fetchFailed' ? 'Failed to fetch models' : 'No providers found',
      }
    },
    render,
  })))

  assert.match(html, /Failed to fetch models/, 'ProvidersPanel should render the persistent provider load failure label')
  assert.match(html, /provider API unavailable/, 'ProvidersPanel should render the provider load failure detail')
  assert.match(html, /OpenAI/, 'ProvidersPanel should keep stale providers visible while loadError is visible')
  assert.doesNotMatch(html, /No providers found/, 'ProvidersPanel should not show the empty state while loadError is visible')

  const settingsSource = readFileSync(new URL('../src/components/solonclaw/settings/ModelSettings.vue', import.meta.url), 'utf8')
  const settingsTemplateSource = parse(settingsSource).descriptor.template?.content
  assert.ok(settingsTemplateSource, 'ModelSettings should have a renderable template')

  const settingsCompiled = compileTemplate({
    id: 'model-settings-load-failure-test',
    filename: 'ModelSettings.vue',
    source: settingsTemplateSource,
    compilerOptions: { mode: 'function' },
  })
  assert.equal(settingsCompiled.errors.length, 0, 'ModelSettings template should compile for behavior verification')

  const settingsRender = new Function('Vue', settingsCompiled.code)(await import('vue'))
  const settingsHtml = await renderToString(createSSRApp(defineComponent({
    components: {
      Spin: { template: '<div><slot /></div>' },
      Empty: { props: ['description'], template: '<div class="empty">{{ description }}</div>' },
      Select: { template: '<select />' },
      Input: { template: '<input />' },
      Button: { template: '<button><slot /></button>' },
    },
    setup() {
      return {
        modelsStore: {
          loading: false,
          providers: [staleProvider],
          loadError: 'provider API unavailable',
        },
        defaultProvider: 'openai',
        defaultModel: 'mimo-v2.5',
        fallbackRows: [],
        providerOptions: [{ label: 'OpenAI', value: 'openai' }],
        savingKey: null,
        handleSaveDefault: () => {},
        addFallbackRow: () => {},
        removeFallbackRow: () => {},
        handleSaveFallbacks: () => {},
        t: (key: string) => {
          if (key === 'models.fetchFailed') return 'Failed to fetch models'
          if (key === 'settings.models.noProviders') return 'No providers configured'
          return key
        },
      }
    },
    render: settingsRender,
  })))

  assert.match(settingsHtml, /Failed to fetch models/, 'ModelSettings should render the provider load failure label')
  assert.match(settingsHtml, /provider API unavailable/, 'ModelSettings should render the provider load failure detail')
  assert.match(settingsHtml, /models\.defaultProviderSection/, 'ModelSettings should keep the provider settings form visible while loadError is visible')
  assert.doesNotMatch(settingsHtml, /No providers configured/, 'ModelSettings should not show the empty state while loadError is visible')
} finally {
  console.error = originalConsoleError
  rmSync(tempDir, { recursive: true, force: true })
}
