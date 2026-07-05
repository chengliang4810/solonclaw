export type LlmDialect = 'openai' | 'openai-responses' | 'ollama' | 'gemini' | 'anthropic'
export type ProviderHealthStatus = 'configured' | 'missing_key' | 'unreachable' | 'unchecked'

export interface ProviderDisplayOption {
  readonly labelKey: string
  readonly value: LlmDialect
}

export interface ProviderDialectCatalogItem {
  readonly labelKey?: string
  readonly value?: string
  readonly baseUrlPlaceholder?: string
}

export interface TranslatedProviderDisplayOption {
  readonly label: string
  readonly value: LlmDialect
}

export type ProviderCardFieldKey = 'providerKey' | 'baseUrl' | 'defaultModel' | 'apiKey' | 'healthStatus'

export interface ProviderCardFieldRow {
  readonly key: ProviderCardFieldKey
  readonly labelKey: string
  readonly monospaced: boolean
}

export interface ProviderFormFieldLabelKeys {
  readonly providerKey: string
  readonly name: string
  readonly baseUrl: string
  readonly apiKey: string
  readonly defaultModel: string
  readonly dialect: string
}

export type ProviderDisplayTranslator = (key: string) => string

export const LLM_DIALECT_OPTIONS: readonly ProviderDisplayOption[] = [
  { labelKey: 'models.dialectOpenai', value: 'openai' },
  { labelKey: 'models.dialectOpenaiResponses', value: 'openai-responses' },
  { labelKey: 'models.dialectOllama', value: 'ollama' },
  { labelKey: 'models.dialectGemini', value: 'gemini' },
  { labelKey: 'models.dialectAnthropic', value: 'anthropic' },
] as const

export const PROVIDER_CARD_FIELD_ROWS: readonly ProviderCardFieldRow[] = [
  { key: 'providerKey', labelKey: 'models.providerKey', monospaced: true },
  { key: 'baseUrl', labelKey: 'models.baseUrl', monospaced: true },
  { key: 'defaultModel', labelKey: 'models.providerDefaultModel', monospaced: true },
  { key: 'apiKey', labelKey: 'models.apiKey', monospaced: false },
  { key: 'healthStatus', labelKey: 'models.healthStatus', monospaced: false },
] as const

export const PROVIDER_FORM_FIELD_LABEL_KEYS: ProviderFormFieldLabelKeys = {
  providerKey: 'models.providerKey',
  name: 'models.name',
  baseUrl: 'models.baseUrl',
  apiKey: 'models.apiKey',
  defaultModel: 'models.defaultModel',
  dialect: 'models.dialect',
} as const

const LLM_DIALECT_LABEL_KEYS: Readonly<Record<LlmDialect, string>> = {
  openai: 'models.dialectOpenai',
  'openai-responses': 'models.dialectOpenaiResponses',
  ollama: 'models.dialectOllama',
  gemini: 'models.dialectGemini',
  anthropic: 'models.dialectAnthropic',
} as const

const LLM_DIALECT_SET: ReadonlySet<string> = new Set(LLM_DIALECT_OPTIONS.map(option => option.value))

const BASE_URL_PLACEHOLDERS: Readonly<Partial<Record<LlmDialect, string>>> = {
  ollama: 'http://127.0.0.1:11434',
  gemini: 'https://generativelanguage.googleapis.com',
  anthropic: 'https://api.anthropic.com',
} as const

const HEALTH_LABEL_KEYS: Readonly<Record<ProviderHealthStatus, string>> = {
  configured: 'models.health.configured',
  missing_key: 'models.health.missing_key',
  unreachable: 'models.health.unreachable',
  unchecked: 'models.health.unchecked',
} as const

const API_KEY_STATUS_LABEL_KEYS = {
  configured: 'models.apiKeyConfigured',
  missing: 'models.apiKeyMissing',
} as const

const HEALTH_STATUS_SET: ReadonlySet<string> = new Set([
  'configured',
  'missing_key',
  'unreachable',
  'unchecked',
])

export function translateDialectOptions(t: ProviderDisplayTranslator): TranslatedProviderDisplayOption[] {
  return LLM_DIALECT_OPTIONS.map(option => ({
    label: t(option.labelKey),
    value: option.value,
  }))
}

export function translateDialectCatalogOptions(
  t: ProviderDisplayTranslator,
  catalog: readonly ProviderDialectCatalogItem[] | undefined,
): TranslatedProviderDisplayOption[] {
  return normalizeDialectCatalog(catalog).map(option => ({
    label: t(option.labelKey),
    value: option.value,
  }))
}

export function normalizeDialectCatalog(
  catalog: readonly ProviderDialectCatalogItem[] | undefined,
): ProviderDisplayOption[] {
  const options = (catalog || [])
    .filter(item => item.value && isLlmDialect(item.value))
    .map(item => {
      const value = item.value as LlmDialect
      return {
        labelKey: item.labelKey || LLM_DIALECT_LABEL_KEYS[value],
        value,
      }
    })
  return options.length ? options : [...LLM_DIALECT_OPTIONS]
}

export function translateDialectLabel(value: string, t: ProviderDisplayTranslator): string {
  return isLlmDialect(value) ? t(LLM_DIALECT_LABEL_KEYS[value]) : value
}

export function baseUrlPlaceholderForDialect(value: string): string {
  return isLlmDialect(value) ? BASE_URL_PLACEHOLDERS[value] ?? 'https://api.example.com' : 'https://api.example.com'
}

export function healthLabelKey(value: string | undefined): string {
  return value && isProviderHealthStatus(value) ? HEALTH_LABEL_KEYS[value] : HEALTH_LABEL_KEYS.unchecked
}

export function apiKeyStatusLabelKey(hasApiKey: boolean): string {
  return hasApiKey ? API_KEY_STATUS_LABEL_KEYS.configured : API_KEY_STATUS_LABEL_KEYS.missing
}

function isLlmDialect(value: string): value is LlmDialect {
  return LLM_DIALECT_SET.has(value)
}

function isProviderHealthStatus(value: string): value is ProviderHealthStatus {
  return HEALTH_STATUS_SET.has(value)
}
