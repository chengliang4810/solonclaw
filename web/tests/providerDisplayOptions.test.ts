import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  LLM_DIALECT_OPTIONS,
  baseUrlPlaceholderForDialect,
  healthLabelKey,
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

assert.equal(baseUrlPlaceholderForDialect('ollama'), 'http://127.0.0.1:11434')
assert.equal(baseUrlPlaceholderForDialect('gemini'), 'https://generativelanguage.googleapis.com')
assert.equal(baseUrlPlaceholderForDialect('anthropic'), 'https://api.anthropic.com')
assert.equal(baseUrlPlaceholderForDialect('openai-responses'), 'https://api.example.com')

assert.equal(healthLabelKey('configured'), 'models.health.configured')
assert.equal(healthLabelKey('missing_key'), 'models.health.missing_key')
assert.equal(healthLabelKey('unreachable'), 'models.health.unreachable')
assert.equal(healthLabelKey('unchecked'), 'models.health.unchecked')
assert.equal(healthLabelKey('unknown'), 'models.health.unchecked')

assert.ok(!providerCard.includes('function dialectLabel'), 'provider card should reuse shared dialect display')
assert.ok(!providerCard.includes('function healthLabel'), 'provider card should reuse shared health display')
assert.ok(!providerForm.includes('function dialectLabel'), 'provider form should reuse shared dialect display')
assert.ok(!providerForm.includes('const dialectOptions = ['), 'provider form should reuse shared dialect options')
