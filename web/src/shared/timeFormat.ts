export function formatTimestampText(value?: number | string | Date | null, locale?: string, fallback = '-'): string {
  if (!value) return fallback
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return fallback
  return locale ? date.toLocaleString(locale) : date.toLocaleString()
}
