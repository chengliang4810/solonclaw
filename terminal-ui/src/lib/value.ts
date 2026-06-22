/**
 * 网关 RPC 和磁盘快照都可能跨版本返回松散结构；这里仅做窄化，不修改原始值语义。
 */
export const asArray = <T = unknown>(value: unknown): T[] => (Array.isArray(value) ? (value as T[]) : [])

export const asOptionalArray = <T = unknown>(value: unknown): T[] | undefined =>
  Array.isArray(value) ? (value as T[]) : undefined

export const asNumber = (value: unknown): number | undefined => (typeof value === 'number' ? value : undefined)

export const asOptionalString = (value: unknown): string | undefined =>
  typeof value === 'string' ? value : undefined

export const asStringArray = (value: unknown): string[] => asArray(value).filter((item): item is string => typeof item === 'string')

export const asOptionalStringArray = (value: unknown): string[] | undefined =>
  asOptionalArray(value)?.filter((item): item is string => typeof item === 'string')
