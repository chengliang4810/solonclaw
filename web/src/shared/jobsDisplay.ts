import { asArray, isRecord, listCount, splitTrimmedText, trimText } from './text.ts'

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

export interface JobActionFlagsSource {
  can_edit?: boolean
  can_history?: boolean
  can_pause?: boolean
  can_remove?: boolean
  can_resume?: boolean
  can_retry?: boolean
  can_run?: boolean
  supports_disable_alias?: boolean
  supports_enable_alias?: boolean
  supports_rerun_alias?: boolean
}

export interface JobBadgeSource {
  pending_trigger?: string | null
  no_agent?: boolean | null
  script?: string | null
  wrap_response?: boolean | null
  skills?: readonly unknown[] | null
  context_from?: readonly unknown[] | null
  enabled_toolsets?: readonly unknown[] | null
  model?: string | null
  provider?: string | null
}

export interface JobDeliveryTargetSource {
  platform?: string | null
  chat_id?: string | null
  thread_id?: string | null
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

export function jobActionSummary(t: DashboardTranslator, actions: JobActionFlagsSource): string {
  const labels: string[] = []
  if (actions.can_pause) labels.push(t('jobs.action.pause'))
  if (actions.can_resume) labels.push(t('jobs.action.resume'))
  if (actions.can_run !== false) labels.push(t('jobs.action.runNow'))
  if (actions.can_retry) labels.push(t('jobs.action.retry'))
  if (actions.can_history !== false) labels.push(t('jobs.action.history'))
  if (actions.can_edit !== false) labels.push(t('common.edit'))
  if (actions.can_remove !== false) labels.push(t('common.delete'))
  return labels.length ? labels.join('、') : '—'
}

export function jobAliasSummary(t: DashboardTranslator, actions: JobActionFlagsSource): string {
  const labels: string[] = []
  if (actions.supports_enable_alias) labels.push(t('jobs.alias.enableStart'))
  if (actions.supports_disable_alias) labels.push(t('jobs.alias.disableStop'))
  if (actions.supports_rerun_alias) labels.push(t('jobs.alias.retryRerun'))
  return labels.length ? labels.join('、') : '—'
}

export function jobBadges(t: DashboardTranslator, job: JobBadgeSource): string[] {
  const badges: string[] = []
  const skillsCount = listCount(job.skills)
  const contextCount = listCount(job.context_from)
  const toolsetsCount = listCount(job.enabled_toolsets)
  if (job.pending_trigger) badges.push(t('jobs.badge.pendingTrigger', { trigger: job.pending_trigger }))
  if (job.no_agent) badges.push(t('jobs.badge.noAgent'))
  if (job.script) badges.push(t('jobs.badge.script'))
  if (job.wrap_response) badges.push(t('jobs.badge.wrapResponse'))
  if (skillsCount > 0) badges.push(t('jobs.badge.skills', { count: skillsCount }))
  if (contextCount > 0) badges.push(t('jobs.badge.context', { count: contextCount }))
  if (toolsetsCount > 0) badges.push(t('jobs.badge.toolsets', { count: toolsetsCount }))
  if (job.model) badges.push(job.provider ? `${job.provider}:${job.model}` : job.model)
  return badges
}

export function jobDeliveryTargetLabel(t: DashboardTranslator, target: JobDeliveryTargetSource): string {
  const parts = [humanizeJobToken(t, target.platform, { fallback: '—' }), target.chat_id || '—']
  if (target.thread_id) parts.push(`#${target.thread_id}`)
  return joinJobDetailParts(parts)
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
