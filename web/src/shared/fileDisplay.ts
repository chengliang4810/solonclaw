const IMAGE_EXTS = new Set(['.png', '.jpg', '.jpeg', '.gif', '.svg', '.webp', '.bmp', '.ico'])
const MARKDOWN_EXTS = new Set(['.md', '.markdown'])
const BINARY_EXTS = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp', '.ico', '.zip', '.gz', '.tar', '.7z', '.rar', '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx', '.mp3', '.mp4', '.wav', '.webm', '.avi', '.mov', '.exe', '.dll', '.so', '.dylib', '.bin', '.dat', '.db', '.sqlite'])

function fileExt(name: string): string {
  const idx = name.lastIndexOf('.')
  return idx >= 0 ? name.slice(idx).toLowerCase() : ''
}

export function isImageFileName(name: string): boolean {
  return IMAGE_EXTS.has(fileExt(name))
}

export function isMarkdownFileName(name: string): boolean {
  return MARKDOWN_EXTS.has(fileExt(name))
}

export function isTextFileName(name: string): boolean {
  return !BINARY_EXTS.has(fileExt(name))
}

export function fileOpenMode(entry: { isDir: boolean; name: string }): 'edit' | 'navigate' | 'none' | 'preview' {
  if (entry.isDir) return 'navigate'
  if (isImageFileName(entry.name) || isMarkdownFileName(entry.name)) return 'preview'
  if (isTextFileName(entry.name)) return 'edit'
  return 'none'
}
