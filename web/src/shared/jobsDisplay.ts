import { asArray, isRecord, splitTrimmedText, trimText } from './text.ts'

export type DashboardTranslator = (key: string, params?: Record<string, unknown>) => string

const DEFAULT_TOKEN_PATHS = ['jobs.humanize'] as const
const GUIDE_TOKEN_PATHS = ['jobs.humanize', 'jobs.fieldHumanize', 'jobs.actionHumanize'] as const

export interface JobScheduleSource {
  schedule?: unknown
  schedule_display?: string | null
}

export interface JobStatusSource {
  enabled?: boolean | null
  state?: string | null
}

export type JobStatusTone = 'success' | 'info' | 'warning' | 'error'

export interface HumanizeJobTokenOptions {
  paths?: readonly string[]
  fallback?: string
  splitUnknown?: boolean
  splitSeparator?: string
}

function translatedLabel(t: DashboardTranslator, path: string, fallback = ''): string {
  const translated = t(path)
  return translated === path ? fallback : translated
}

function lookupTokenLabel(t: DashboardTranslator, token: string, paths: readonly string[]): string {
  if (!/^[A-Za-z0-9_.-]+$/.test(token)) return ''
  for (const path of paths) {
    const label = translatedLabel(t, `${path}.${token}`)
    if (label) return label
  }
  return ''
}

/**
 * 任务中心的后端字段是协议 token，展示时先查本地 i18n；未配置翻译时保留原 token，避免改变接口语义。
 */
export function humanizeJobToken(
  t: DashboardTranslator,
  value?: string | null,
  options: HumanizeJobTokenOptions = {},
): string {
  const normalized = trimText(value)
  const fallback = options.fallback ?? ''
  if (!normalized) return fallback

  const paths = options.paths ?? DEFAULT_TOKEN_PATHS
  const direct = lookupTokenLabel(t, normalized, paths)
  if (direct) return direct

  if (options.splitUnknown) {
    const parts = splitTrimmedText(normalized, /[_-]/g)
    if (parts.length > 0) {
      return parts
        .map(part => lookupTokenLabel(t, part, paths) || part)
        .join(options.splitSeparator ?? ' / ')
    }
  }

  return normalized
}

/**
 * 任务说明区需要比卡片更宽的翻译范围，兼容字段名、动作名和复合 token 的展示。
 */
export function humanizeGuideToken(t: DashboardTranslator, value?: string | null): string {
  return humanizeJobToken(t, value, {
    paths: GUIDE_TOKEN_PATHS,
    splitUnknown: true,
    splitSeparator: ' / ',
  })
}

export function jobTokenListText(
  t: DashboardTranslator,
  value?: string[] | null,
  options: { fallback?: string; separator?: string; guide?: boolean } = {},
): string {
  const items = asArray<string>(value).filter(Boolean)
  if (items.length === 0) return options.fallback ?? '—'
  return items
    .map(item => options.guide ? humanizeGuideToken(t, item) : humanizeJobToken(t, item))
    .join(options.separator ?? '、')
}

export function jobMapKeysText(t: DashboardTranslator, value?: Record<string, unknown> | null): string {
  if (!value) return '—'
  return Object.keys(value).map(item => humanizeGuideToken(t, item)).join('、')
}

export function jobValueText(t: DashboardTranslator, value: unknown): string {
  if (Array.isArray(value)) return value.map(item => humanizeGuideToken(t, String(item))).join('、')
  if (typeof value === 'boolean') return value ? t('jobs.detail.yes') : t('jobs.detail.no')
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'string') return humanizeGuideToken(t, value)
  return String(value)
}

export function jobValueList(t: DashboardTranslator, value: unknown): string[] {
  if (Array.isArray(value)) return value.map(item => jobValueText(t, item)).filter(item => item && item !== '—')
  if (typeof value === 'string' && trimText(value)) return [trimText(value)]
  return []
}

export function jobScheduleLabel(job: JobScheduleSource, fallback = '—'): string {
  const schedule = job.schedule
  if (typeof schedule === 'string') return schedule
  if (isRecord(schedule)) {
    const display = schedule.display || schedule.expr || schedule.raw
    if (display) return String(display)
  }
  return job.schedule_display || fallback
}

export function jobStatusLabel(t: DashboardTranslator, job: JobStatusSource): string {
  if (job.state === 'running') return t('jobs.status.running')
  if (job.state === 'paused') return t('jobs.status.paused')
  if (!job.enabled) return t('jobs.status.disabled')
  return t('jobs.status.scheduled')
}

export function jobStatusTone(job: JobStatusSource): JobStatusTone {
  if (job.state === 'running') return 'info'
  if (job.state === 'paused') return 'warning'
  if (!job.enabled) return 'error'
  return 'success'
}

/**
 * 调度类型推断只服务 Dashboard 控件展示；真实执行语义仍以后端返回的 schedule.kind 为准。
 */
export function inferJobScheduleKind(job: JobScheduleSource): string {
  const schedule = job.schedule
  if (isRecord(schedule) && schedule.kind) return String(schedule.kind)
  const expr = trimText(jobScheduleLabel(job, ''))
  if (/^every\s+\d+/i.test(expr)) return 'interval'
  if (/^\d+\s*(m|min|minute|minutes|h|hr|hrs|hour|hours|d|day|days)$/i.test(expr)) return 'once'
  if (/^\d{4}-\d{2}-\d{2}T/.test(expr)) return 'once'
  return 'cron'
}

export function formatJobTime(value?: string | null): string {
  if (!value) return '—'
  return new Date(value).toLocaleString()
}

export function jobListDetail(values?: string[] | null, separator = ', '): string {
  const list = asArray<string>(values)
  return list.length ? list.join(separator) : '—'
}

export function joinJobDetailParts(
  parts: Array<string | null | undefined>,
  options: { separator?: string; fallback?: string } = {},
): string {
  const values = parts.filter(Boolean) as string[]
  if (values.length === 0) return options.fallback ?? ''
  return values.join(options.separator ?? ' · ')
}
