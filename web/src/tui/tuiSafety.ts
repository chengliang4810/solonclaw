const OSC52_MAX_CHARS = 100000
const URL_PATTERN = /\bhttps?:\/\/[^\s<>"')]+/gi

export function extractSafeUrls(text: string): string[] {
  const matches = text.match(URL_PATTERN) || []
  const unique = new Set<string>()
  for (const match of matches) {
    const trimmed = match.replace(/[.,;:!?]+$/, '')
    if (isSafeExternalUrl(trimmed)) {
      unique.add(trimmed)
    }
  }
  return Array.from(unique).slice(0, 5)
}

export function isSafeExternalUrl(value: string): boolean {
  try {
    const url = new URL(value, window.location.origin)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return false
    const host = url.hostname.toLowerCase()
    if (host === 'localhost' || host.endsWith('.localhost')) return false
    if (host === '0.0.0.0' || host === '127.0.0.1' || host === '::1') return false
    if (/^10\./.test(host) || /^192\.168\./.test(host)) return false
    if (/^172\.(1[6-9]|2\d|3[0-1])\./.test(host)) return false
    if (/^169\.254\./.test(host)) return false
    return true
  } catch {
    return false
  }
}

export function openSafeUrl(value: string): boolean {
  if (!isSafeExternalUrl(value)) return false
  window.open(value, '_blank', 'noopener,noreferrer')
  return true
}

export async function writeClipboard(text: string): Promise<boolean> {
  if (!navigator.clipboard || !window.isSecureContext) return false
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

export async function readClipboard(): Promise<string | null> {
  if (!navigator.clipboard || !window.isSecureContext) return null
  try {
    return await navigator.clipboard.readText()
  } catch {
    return null
  }
}

export async function handleOsc52(sequence: string): Promise<{ ok: boolean; text?: string; error?: string }> {
  const decoded = decodeOsc52(sequence)
  if (!decoded) return { ok: false, error: 'OSC52 内容无效' }
  if (decoded.length > OSC52_MAX_CHARS) return { ok: false, error: 'OSC52 内容过长，已拒绝写入剪贴板' }
  const ok = await writeClipboard(decoded)
  return ok ? { ok: true, text: decoded } : { ok: false, error: '浏览器不允许写入剪贴板' }
}

function decodeOsc52(sequence: string): string | null {
  const marker = sequence.includes(';') ? sequence.slice(sequence.indexOf(';') + 1) : sequence
  const encoded = marker.trim()
  if (!encoded || encoded === '?') return null
  try {
    const binary = atob(encoded)
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i += 1) {
      bytes[i] = binary.charCodeAt(i)
    }
    return new TextDecoder().decode(bytes)
  } catch {
    return null
  }
}

export function sanitizeInputForDisplay(text: string): string {
  return text.replace(/\u001b\][\s\S]*?(\u0007|\u001b\\)/g, '[OSC]').replace(/\p{C}/gu, '')
}
