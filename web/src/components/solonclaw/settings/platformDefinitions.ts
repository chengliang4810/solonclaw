import type { PlatformCatalogItem } from '@/api/solonclaw/config'

export const PLATFORM_SETTINGS_KEYS = [
  'feishu',
  'dingtalk',
  'wecom',
  'weixin',
  'qqbot',
  'yuanbao',
] as const

export type PlatformSettingsKey = (typeof PLATFORM_SETTINGS_KEYS)[number]

type PlatformSettingsItem = {
  readonly key: PlatformSettingsKey
  readonly name: string
  readonly icon: string
}

type PlatformFallback = {
  readonly key: PlatformSettingsKey
  readonly name: string
  readonly iconKey: PlatformSettingsKey
  readonly order: number
}

type PlatformCatalogSourceItem = PlatformFallback & {
  readonly displayName?: string
  readonly enabled?: boolean
}

const PLATFORM_FALLBACKS = [
  { key: 'feishu', name: '飞书', iconKey: 'feishu', order: 10 },
  { key: 'dingtalk', name: '钉钉', iconKey: 'dingtalk', order: 20 },
  { key: 'wecom', name: '企业微信', iconKey: 'wecom', order: 30 },
  { key: 'weixin', name: '微信', iconKey: 'weixin', order: 40 },
  { key: 'qqbot', name: 'QQBot', iconKey: 'qqbot', order: 50 },
  { key: 'yuanbao', name: '腾讯元宝', iconKey: 'yuanbao', order: 60 },
] as const satisfies readonly PlatformFallback[]

const DEFAULT_PLATFORM_CATALOG_SOURCE: readonly PlatformCatalogSourceItem[] = PLATFORM_FALLBACKS.map(item => ({
  ...item,
}))

export const PLATFORM_ICON_SVG_BY_KEY: Record<PlatformSettingsKey, string> = {
  feishu:
    '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M6.59 3.41a2.25 2.25 0 0 1 3.182 0L13.5 7.14l-3.182 3.182L6.59 7.59a2.25 2.25 0 0 1 0-3.182zm5.303 5.303L15.075 5.53a2.25 2.25 0 0 1 3.182 3.182L15.075 11.894 11.893 8.713zM3.41 6.59a2.25 2.25 0 0 1 3.182 0l3.182 3.182-3.182 3.182a2.25 2.25 0 0 1-3.182-3.182L3.41 6.59zm5.303 5.303L11.894 15.075a2.25 2.25 0 0 1-3.182 3.182L5.53 15.075 8.713 11.893zm5.303-5.303L17.478 9.778a2.25 2.25 0 0 1-3.182 3.182L10.53 10.075l3.182-3.182 0 .023z"/></svg>',
  dingtalk:
    '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M18.53 3.47c-1.71-1.71-4.87-2.18-8.02-.98-2.35.9-4.4 2.72-5.58 4.88-.74 1.37-1.02 2.74-.83 4.01.21 1.47.95 2.61 2.12 3.28 1.36.78 3.22.86 5.22.19-.45.72-1.19 1.38-2.14 1.92-1.14.65-2.38.96-3.4.9 1.12 1.03 2.72 1.54 4.52 1.38 1.83-.17 3.63-1.03 5.08-2.39 1.75-1.63 2.8-3.8 2.96-5.97.09-1.19-.11-2.25-.57-3.11.92.18 1.74.62 2.31 1.29.14-1.98-.44-3.83-1.67-5.06zm-7.7 8.96c-.86 0-1.56-.7-1.56-1.56s.7-1.56 1.56-1.56 1.56.7 1.56 1.56-.7 1.56-1.56 1.56z"/></svg>',
  weixin:
    '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M8.691 2.188C3.891 2.188 0 5.476 0 9.53c0 2.212 1.17 4.203 3.002 5.55a.59.59 0 01.213.665l-.39 1.48c-.019.07-.048.141-.048.213 0 .163.13.295.29.295a.326.326 0 00.167-.054l1.903-1.114a.864.864 0 01.717-.098 10.16 10.16 0 002.837.403c.276 0 .543-.027.811-.05-.857-2.578.157-4.972 1.932-6.446 1.703-1.415 3.882-1.98 5.853-1.838-.576-3.583-4.196-6.348-8.596-6.348zM5.785 5.991c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 01-1.162 1.178A1.17 1.17 0 014.623 7.17c0-.651.52-1.18 1.162-1.18zm5.813 0c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 01-1.162 1.178 1.17 1.17 0 01-1.162-1.178c0-.651.52-1.18 1.162-1.18zm3.68 4.025c-3.694 0-6.69 2.462-6.69 5.496 0 3.034 2.996 5.496 6.69 5.496.753 0 1.477-.1 2.158-.28a.66.66 0 01.548.074l1.46.854a.25.25 0 00.127.041.224.224 0 00.221-.225c0-.055-.022-.109-.037-.162l-.298-1.131a.453.453 0 01.163-.509C21.81 18.613 22.77 16.973 22.77 15.512c0-3.034-2.996-5.496-6.69-5.496h.198zm-2.454 3.347c.491 0 .889.404.889.902a.896.896 0 01-.889.903.896.896 0 01-.889-.903c0-.498.398-.902.889-.902zm4.912 0c.491 0 .889.404.889.902a.896.896 0 01-.889.903.896.896 0 01-.889-.903c0-.498.398-.902.889-.902z"/></svg>',
  wecom:
    '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M8.691 2.188C3.891 2.188 0 5.476 0 9.53c0 2.212 1.17 4.203 3.002 5.55a.59.59 0 01.213.665l-.39 1.48c-.019.07-.048.141-.048.213 0 .163.13.295.29.295a.326.326 0 00.167-.054l1.903-1.114a.864.864 0 01.717-.098 10.16 10.16 0 002.837.403c.276 0 .543-.027.811-.05-.857-2.578.157-4.972 1.932-6.446 1.703-1.415 3.882-1.98 5.853-1.838-.576-3.583-4.196-6.348-8.596-6.348zM5.785 5.991c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 01-1.162 1.178A1.17 1.17 0 014.623 7.17c0-.651.52-1.18 1.162-1.18zm5.813 0c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 01-1.162 1.178 1.17 1.17 0 01-1.162-1.178c0-.651.52-1.18 1.162-1.18zm3.68 4.025c-3.694 0-6.69 2.462-6.69 5.496 0 3.034 2.996 5.496 6.69 5.496.753 0 1.477-.1 2.158-.28a.66.66 0 01.548.074l1.46.854a.25.25 0 00.127.041.224.224 0 00.221-.225c0-.055-.022-.109-.037-.162l-.298-1.131a.453.453 0 01.163-.509C21.81 18.613 22.77 16.973 22.77 15.512c0-3.034-2.996-5.496-6.69-5.496h.198zm-2.454 3.347c.491 0 .889.404.889.902a.896.896 0 01-.889.903.896.896 0 01-.889-.903c0-.498.398-.902.889-.902zm4.912 0c.491 0 .889.404.889.902a.896.896 0 01-.889.903.896.896 0 01-.889-.903c0-.498.398-.902.889-.902z"/></svg>',
  qqbot:
    '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.477 2 2 5.806 2 10.5c0 2.684 1.464 5.076 3.75 6.633V22l4.117-2.278c.688.111 1.401.168 2.133.168 5.523 0 10-3.806 10-8.5S17.523 2 12 2zm-3 9.25A1.25 1.25 0 1110.25 10 1.25 1.25 0 019 11.25zm6 0A1.25 1.25 0 1116.25 10 1.25 1.25 0 0115 11.25z"/></svg>',
  yuanbao:
    '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M11.27 2.58a1 1 0 011.46 0l2.07 2.24a1 1 0 00.55.3l3 .64a1 1 0 01.56 1.67l-2.04 2.29a1 1 0 00-.24.61l-.32 3.05a1 1 0 01-1.33.84l-2.86-1a1 1 0 00-.66 0l-2.86 1a1 1 0 01-1.33-.84l-.32-3.05a1 1 0 00-.24-.61L5.09 7.43a1 1 0 01.56-1.67l3-.64a1 1 0 00.55-.3l2.07-2.24zm.73 13.92c1.34 0 2.61.29 3.75.8V19a1 1 0 01-1.45.89L12 18.76l-2.3 1.13A1 1 0 018.25 19v-1.7c1.14-.51 2.41-.8 3.75-.8z"/></svg>',
} as const

export function normalizePlatformSettingsItems(
  catalog: readonly PlatformCatalogItem[],
): readonly PlatformSettingsItem[] {
  const fallbackByKey = new Map(PLATFORM_FALLBACKS.map(item => [item.key, item]))
  const source = catalog.length > 0
    ? catalog
      .filter(item => isPlatformSettingsKey(item.code))
      .flatMap(item => mergeCatalogItem(item, fallbackByKey))
    : DEFAULT_PLATFORM_CATALOG_SOURCE
  return source
    .filter(item => item.enabled !== false)
    .map(item => {
      const fallback = fallbackByKey.get(item.key)
      const iconKey = isPlatformSettingsKey(item.iconKey) ? item.iconKey : fallback?.iconKey
      return {
        key: item.key,
        name: item.displayName || fallback?.name || item.key,
        icon: PLATFORM_ICON_SVG_BY_KEY[iconKey || item.key],
        order: item.order ?? fallback?.order ?? 0,
      }
    })
    .sort((left, right) => left.order - right.order)
    .map(({ key, name, icon }) => ({ key, name, icon }))
}

function isPlatformSettingsKey(value: unknown): value is PlatformSettingsKey {
  return value === 'feishu'
    || value === 'dingtalk'
    || value === 'wecom'
    || value === 'weixin'
    || value === 'qqbot'
    || value === 'yuanbao'
}

function mergeCatalogItem(
  item: PlatformCatalogItem,
  fallbackByKey: ReadonlyMap<PlatformSettingsKey, PlatformFallback>,
): readonly PlatformCatalogSourceItem[] {
  if (!isPlatformSettingsKey(item.code)) return []
  const fallback = fallbackByKey.get(item.code)
  if (!fallback) return []
  return [{
    ...fallback,
    displayName: item.displayName,
    iconKey: isPlatformSettingsKey(item.iconKey) ? item.iconKey : fallback.iconKey,
    order: item.order ?? fallback.order,
    enabled: item.enabled,
  }]
}
