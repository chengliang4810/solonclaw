export type StringLike = string | null | undefined

/**
 * Dashboard 表单经常同时接收手输文本和 API 空值；统一裁剪规则，避免各组件各写一套空白判断。
 */
export function trimText(value: StringLike): string {
  return (value || '').trim()
}

/**
 * 用于按钮可用性、必填校验等场景，只把裁剪后仍有内容的字符串视为有效输入。
 */
export function hasText(value: StringLike): boolean {
  return trimText(value).length > 0
}

/**
 * 将逗号、换行或自定义分隔符输入归一成非空文本数组；保留调用方传入的分隔语义。
 */
export function splitTrimmedText(value: StringLike, delimiter: string | RegExp): string[] {
  const text = trimText(value)
  if (!text) return []
  return text
    .split(delimiter)
    .map(item => item.trim())
    .filter(Boolean)
}

/**
 * 对后端快照字段做安全数组读取，避免模板和计算属性反复写 Array.isArray 分支。
 */
export function asArray<T>(value: unknown): T[] {
  return Array.isArray(value) ? value as T[] : []
}

/**
 * 只关心列表是否有元素的展示判断统一走这里，降低 length 判空的重复噪音。
 */
export function hasItems(value: readonly unknown[] | null | undefined): boolean {
  return Array.isArray(value) && value.length > 0
}

/**
 * 读取未知结构的列表长度，适合 API 快照里 tools/scopes 这类可能缺省的数组字段。
 */
export function listCount(value: unknown): number {
  return Array.isArray(value) ? value.length : 0
}

/**
 * 表单编辑区把后端数组还原成文本输入时使用，避免各处重复处理空数组和分隔符。
 */
export function joinTextList(value: readonly string[] | null | undefined, separator = ', '): string {
  return Array.isArray(value) ? value.join(separator) : ''
}

/**
 * 判断普通对象时排除数组，避免 JSON 配置和工具快照展示把数组误当键值对象处理。
 */
export function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

/**
 * JSON 配置预览统一处理空值、纯字符串和结构化对象，保证编辑弹窗与详情面板显示一致。
 */
export function displayJson(value: unknown, options: { emptyText?: string } = {}): string {
  const emptyText = options.emptyText ?? '-'
  if (value === null || value === undefined || value === '') return emptyText
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}
