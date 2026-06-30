import { displayJson } from './text.ts'

export type RunsDisplayTranslator = (key: string, params?: Readonly<Record<string, unknown>>) => string

/**
 * 运行记录里的时间戳来自后端毫秒值；空值与 0 都按无数据处理，保持列表旧展示语义。
 */
export function runTimestampText(value?: number | null, fallback = '-'): string {
  if (!value) return fallback
  return new Date(value).toLocaleString()
}

/**
 * 后端运行状态是协议 token；只对稳定核心状态做本地化，其余状态原样展示便于排查。
 */
export function runStatusLabel(value: string | null | undefined, t: RunsDisplayTranslator): string {
  const normalized = (value || '').trim().toLowerCase()
  switch (normalized) {
    case 'ok':
      return t('runs.status.success')
    case 'error':
    case 'failed':
      return t('runs.status.failed')
    case 'running':
      return t('runs.status.running')
    default:
      return value || '-'
  }
}

/**
 * 工具审计字段允许后端缺省，缺省时用占位符而不是误判为否。
 */
export function runBooleanLabel(value: boolean | null | undefined, t: RunsDisplayTranslator): string {
  if (value === true) return t('common.yes')
  if (value === false) return t('common.no')
  return '-'
}

/**
 * 会话产物可能是纯文本或结构化 JSON；统一走 JSON 预览规则，避免页面各自格式化。
 */
export function runArtifactText(value: unknown, t: RunsDisplayTranslator): string {
  if (!value) return t('runs.noSessionArtifact')
  return displayJson(value, { emptyText: t('runs.noSessionArtifact') })
}
