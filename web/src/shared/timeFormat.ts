export function formatTimestampText(value?: number | null, locale?: string): string {
  if (!value) return '-'
  const date = new Date(value)
  return locale ? date.toLocaleString(locale) : date.toLocaleString()
}
