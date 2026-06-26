const SOURCE_LABELS: Record<string, string> = {
  telegram: 'Telegram',
  api_server: 'API 接入',
  cli: '本地命令行',
  discord: 'Discord',
  slack: 'Slack',
  matrix: 'Matrix',
  whatsapp: 'WhatsApp',
  signal: 'Signal',
  email: 'Email',
  sms: '短信',
  dingtalk: '钉钉',
  feishu: '飞书',
  wecom: '企业微信',
  weixin: '微信',
  bluebubbles: 'iMessage',
  mattermost: 'Mattermost',
  cron: '定时任务',
}

export function getSourceLabel(source?: string): string {
  if (!source) return ''
  return SOURCE_LABELS[source] || source
}

export function formatTimestampMs(timestamp: number): string {
  if (!timestamp) return ''
  const date = new Date(timestamp)
  const now = new Date()
  if (date.toDateString() === now.toDateString()) {
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  }
  return date.toLocaleDateString([], { month: 'short', day: 'numeric' })
}

export function formatTimestampSeconds(timestamp: number): string {
  return formatTimestampMs(timestamp * 1000)
}

export function formatLocalDateTimeMs(value?: number | null): string {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}
