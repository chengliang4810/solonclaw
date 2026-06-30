export type LogLevel = 'ERROR' | 'WARNING' | 'INFO' | 'DEBUG'
export type LogLevelFilter = '' | LogLevel
export type LogLevelClass = 'level-error' | 'level-warning' | 'level-info' | 'level-debug'

export type LogLevelOption =
  | {
      readonly labelKey: 'logs.all'
      readonly value: ''
    }
  | {
      readonly label: LogLevel
      readonly value: LogLevel
    }

export interface TranslatedLogLevelOption {
  readonly label: string
  readonly value: LogLevelFilter
}

export interface LogLineCountOption {
  readonly label: string
  readonly value: number
}

export type LogLevelTranslator = (key: string) => string

export const LOG_LEVEL_OPTIONS: readonly LogLevelOption[] = [
  { labelKey: 'logs.all', value: '' },
  { label: 'ERROR', value: 'ERROR' },
  { label: 'WARNING', value: 'WARNING' },
  { label: 'INFO', value: 'INFO' },
  { label: 'DEBUG', value: 'DEBUG' },
] as const

export const LOG_LINE_COUNT_OPTIONS: readonly LogLineCountOption[] = [
  { label: '50', value: 50 },
  { label: '100', value: 100 },
  { label: '200', value: 200 },
  { label: '500', value: 500 },
] as const

const LOG_LEVEL_CLASS_MAP: Readonly<Record<LogLevel, LogLevelClass>> = {
  ERROR: 'level-error',
  WARNING: 'level-warning',
  INFO: 'level-info',
  DEBUG: 'level-debug',
} as const

const LOG_LEVEL_SET: ReadonlySet<string> = new Set(['ERROR', 'WARNING', 'INFO', 'DEBUG'])

export function translateLogLevelOptions(t: LogLevelTranslator): TranslatedLogLevelOption[] {
  return LOG_LEVEL_OPTIONS.map(option => ({
    label: 'label' in option ? option.label : t(option.labelKey),
    value: option.value,
  }))
}

function isLogLevel(level: string): level is LogLevel {
  return LOG_LEVEL_SET.has(level)
}

export function logLevelClass(level: string): LogLevelClass {
  return isLogLevel(level) ? LOG_LEVEL_CLASS_MAP[level] : 'level-info'
}
