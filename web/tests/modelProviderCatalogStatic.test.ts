import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'

const providerPresetFile = new URL('../src/shared/providers.ts', import.meta.url)
const modelSelectorFile = new URL('../src/components/layout/ModelSelector.vue', import.meta.url)
const systemApiFile = new URL('../src/api/solonclaw/system.ts', import.meta.url)

const modelSelector = readFileSync(modelSelectorFile, 'utf8')
const systemApi = readFileSync(systemApiFile, 'utf8')

assert.equal(
  existsSync(providerPresetFile),
  false,
  'frontend should not keep a hard-coded provider/model preset table after /api/providers became the catalog source',
)
assert.ok(
  modelSelector.includes('appStore.modelGroups'),
  'model selector should render provider groups from backend-loaded app store state',
)
assert.ok(
  systemApi.includes("request<ProvidersPayload>('/api/providers')"),
  'system API should use the backend provider catalog endpoint as the provider source',
)
