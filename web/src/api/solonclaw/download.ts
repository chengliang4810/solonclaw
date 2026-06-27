import { getFileDownloadUrl } from './files'

export function getDownloadUrl(filePath: string, fileName?: string): string {
  return getFileDownloadUrl(filePath, fileName)
}

export async function downloadFile(filePath: string, fileName?: string): Promise<void> {
  const a = document.createElement('a')
  a.href = getDownloadUrl(filePath, fileName)
  a.download = fileName || filePath.split('/').pop() || 'download.txt'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}
