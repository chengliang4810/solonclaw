export const DOMESTIC_PLATFORM_KEYS = [
  'feishu',
  'dingtalk',
  'wecom',
  'weixin',
  'qqbot',
  'yuanbao',
] as const

export type DomesticPlatformKey = (typeof DOMESTIC_PLATFORM_KEYS)[number]

const DOMESTIC_PLATFORM_KEY_SET: ReadonlySet<string> = new Set(DOMESTIC_PLATFORM_KEYS)

export const DOMESTIC_PLATFORM_LABEL_KEYS: Record<DomesticPlatformKey, string> = {
  feishu: 'jobs.platformFeishu',
  dingtalk: 'jobs.platformDingtalk',
  wecom: 'jobs.platformWecom',
  weixin: 'jobs.platformWeixin',
  qqbot: 'jobs.platformQqbot',
  yuanbao: 'jobs.platformYuanbao',
} as const

export function isDomesticPlatformKey(value: unknown): value is DomesticPlatformKey {
  if (typeof value !== 'string') return false
  return DOMESTIC_PLATFORM_KEY_SET.has(value)
}
