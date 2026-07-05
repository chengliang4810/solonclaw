import { getFileDownloadUrl } from './files'
import { dashboardFetch, getApiKey } from '../client'

export function getDownloadUrl(filePath: string, fileName?: string): string {
  return getFileDownloadUrl(filePath, fileName)
}

export async function downloadFile(filePath: string, fileName?: string): Promise<void> {
  const headers = new Headers()
  const apiKey = getApiKey()
  if (apiKey) {
    headers.set('Authorization', `Bearer ${apiKey}`)
  }
  const res = await dashboardFetch(getDownloadUrl(filePath, fileName), { headers })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `Download failed: ${res.status}`)
  }
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName || filePath.split('/').pop() || 'download.txt'
  document.body.appendChild(a)
  try {
    a.click()
  } finally {
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }
}
