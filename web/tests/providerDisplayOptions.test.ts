import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  LLM_DIALECT_OPTIONS,
  PROVIDER_CARD_FIELD_ROWS,
  PROVIDER_FORM_FIELD_LABEL_KEYS,
  apiKeyStatusLabelKey,
  baseUrlPlaceholderForDialect,
  healthLabelKey,
  normalizeDialectCatalog,
  translateDialectCatalogOptions,
  translateDialectLabel,
  translateDialectOptions,
} from '../src/shared/providerDisplay.ts'

const providerCard = readFileSync(
  new URL('../src/components/solonclaw/models/ProviderCard.vue', import.meta.url),
  'utf8',
)
const providerForm = readFileSync(
  new URL('../src/components/solonclaw/models/ProviderFormModal.vue', import.meta.url),
  'utf8',
)

assert.deepEqual(
  LLM_DIALECT_OPTIONS.map(item => item.value),
  ['openai', 'openai-responses', 'ollama', 'gemini', 'anthropic'],
)

const translated = translateDialectOptions(key => `label:${key}`)
assert.deepEqual(translated.map(item => item.label), [
  'label:models.dialectOpenai',
  'label:models.dialectOpenaiResponses',
  'label:models.dialectOllama',
  'label:models.dialectGemini',
  'label:models.dialectAnthropic',
])

assert.equal(translateDialectLabel('openai-responses', key => `label:${key}`), 'label:models.dialectOpenaiResponses')
assert.equal(translateDialectLabel('custom-protocol', key => `label:${key}`), 'custom-protocol')

const backendCatalog = normalizeDialectCatalog([
  { value: 'anthropic', labelKey: 'models.dialectAnthropic' },
  { value: 'unsupported', labelKey: 'models.unsupportedDialect' },
  { value: 'ollama' },
])
assert.deepEqual(backendCatalog.map(item => item.value), ['anthropic', 'ollama'])
assert.deepEqual(translateDialectCatalogOptions(key => `label:${key}`, backendCatalog), [
  { label: 'label:models.dialectAnthropic', value: 'anthropic' },
  { label: 'label:models.dialectOllama', value: 'ollama' },
])
assert.deepEqual(
  translateDialectCatalogOptions(key => `label:${key}`, []).map(item => item.value),
  ['openai', 'openai-responses', 'ollama', 'gemini', 'anthropic'],
)

assert.equal(baseUrlPlaceholderForDialect('ollama'), 'http://127.0.0.1:11434')
assert.equal(baseUrlPlaceholderForDialect('gemini'), 'https://generativelanguage.googleapis.com')
assert.equal(baseUrlPlaceholderForDialect('anthropic'), 'https://api.anthropic.com')
assert.equal(baseUrlPlaceholderForDialect('openai-responses'), 'https://api.example.com')

assert.equal(healthLabelKey('configured'), 'models.health.configured')
assert.equal(healthLabelKey('missing_key'), 'models.health.missing_key')
assert.equal(healthLabelKey('unreachable'), 'models.health.unreachable')
assert.equal(healthLabelKey('unchecked'), 'models.health.unchecked')
assert.equal(healthLabelKey('unknown'), 'models.health.unchecked')

assert.deepEqual(
  PROVIDER_CARD_FIELD_ROWS.map(item => item.labelKey),
  [
    'models.providerKey',
    'models.baseUrl',
    'models.providerDefaultModel',
    'models.apiKey',
    'models.healthStatus',
  ],
)
assert.deepEqual(PROVIDER_FORM_FIELD_LABEL_KEYS, {
  providerKey: 'models.providerKey',
  name: 'models.name',
  baseUrl: 'models.baseUrl',
  apiKey: 'models.apiKey',
  defaultModel: 'models.defaultModel',
  dialect: 'models.dialect',
})
assert.equal(apiKeyStatusLabelKey(true), 'models.apiKeyConfigured')
assert.equal(apiKeyStatusLabelKey(false), 'models.apiKeyMissing')

assert.ok(!providerCard.includes('function dialectLabel'), 'provider card should reuse shared dialect display')
assert.ok(!providerCard.includes('function healthLabel'), 'provider card should reuse shared health display')
assert.ok(!providerForm.includes('function dialectLabel'), 'provider form should reuse shared dialect display')
assert.ok(!providerForm.includes('const dialectOptions = ['), 'provider form should reuse shared dialect options')
assert.ok(
  providerForm.includes('translateDialectCatalogOptions(t, modelsStore.dialectCatalog)'),
  'provider form should prefer backend provider dialect catalog from the models store',
)
assert.ok(!providerCard.includes("t('models.providerKey')"), 'provider card should reuse shared field labels')
assert.ok(!providerCard.includes("t('models.baseUrl')"), 'provider card should reuse shared field labels')
assert.ok(!providerCard.includes("t('models.providerDefaultModel')"), 'provider card should reuse shared field labels')
assert.ok(!providerCard.includes("t('models.apiKey')"), 'provider card should reuse shared field labels')
assert.ok(!providerCard.includes("t('models.healthStatus')"), 'provider card should reuse shared field labels')
assert.ok(!providerForm.includes("t('models.providerKey')"), 'provider form should reuse shared field labels')
assert.ok(!providerForm.includes("t('models.name')"), 'provider form should reuse shared field labels')
assert.ok(!providerForm.includes("t('models.baseUrl')"), 'provider form should reuse shared field labels')
assert.ok(!providerForm.includes("t('models.apiKey')"), 'provider form should reuse shared field labels')
assert.ok(!providerForm.includes("t('models.defaultModel')"), 'provider form should reuse shared field labels')
assert.ok(!providerForm.includes("t('models.dialect')"), 'provider form should reuse shared field labels')
assert.ok(providerForm.includes('mode="tags"'), 'provider form should support manually adding model names')
assert.ok(providerForm.includes('formData.value.models'), 'provider form should persist the complete Provider model list')
assert.ok(
  providerForm.includes('() => [formData.value.baseUrl, formData.value.apiKey, formData.value.dialect]'),
  'new Provider discovery should react to all connection parameters',
)
assert.ok(providerForm.includes('}, 500)'), 'automatic model discovery should debounce input for 500 ms')
assert.ok(providerForm.includes('if (isEdit.value) return'), 'automatic discovery should only run while adding a Provider')
assert.ok(providerForm.includes('requestId !== modelRequestId'), 'stale model discovery responses should be discarded')
assert.ok(
  providerForm.includes('activeModelRequest?.signature === signature'),
  'identical in-flight model discovery requests should be coalesced',
)
assert.ok(
  providerForm.includes('formData.value.models = [...manualModels.value]'),
  'changing Provider connection parameters should discard models discovered from the previous endpoint',
)
assert.ok(
  providerForm.includes('previousDiscoveredModels.has(currentDefaultModel)'),
  'changing Provider connection parameters should clear a default model discovered from the previous endpoint',
)
assert.ok(
  providerForm.includes('!manualModels.value.includes(currentDefaultModel)'),
  'changing Provider connection parameters should preserve a manually configured default model',
)
assert.ok(
  !providerForm.includes('...discoveredModels.value.map(model => model.trim())'),
  'saving should not restore discovered models that the user removed from the editable list',
)
assert.ok(
  providerForm.includes('...discoveredModels.value,'),
  'the current endpoint discovery result should populate the editable model list',
)
assert.ok(providerForm.includes('@click="fetchModelList(false)"'), 'the form should keep an explicit manual retry action')
