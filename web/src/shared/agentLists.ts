export function parseAgentListInput(raw: string): string[] {
  const text = (raw || '').trim()
  if (!text) return []
  if (text.startsWith('[') || text.startsWith('{')) {
    const parsed = JSON.parse(text)
    if (!Array.isArray(parsed)) {
      throw new Error('agent list must be a JSON array')
    }
    return parsed.map(item => String(item).trim()).filter(Boolean)
  }
  return text
    .split(/[\r\n,，]+/)
    .map(item => item.trim())
    .filter(Boolean)
}

export function previewAgentListInput(raw: string): string[] {
  try {
    return parseAgentListInput(raw)
  } catch {
    return []
  }
}

export function formatAgentListInput(rawJson?: string): string {
  return parseAgentListInput(rawJson || '[]').join('\n')
}

export function serializeAgentListInput(raw: string): string {
  return JSON.stringify(parseAgentListInput(raw))
}
