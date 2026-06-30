const EXT_LANG_MAP: Record<string, string> = {
  '.js': 'javascript', '.jsx': 'javascript',
  '.ts': 'typescript', '.tsx': 'typescript',
  '.json': 'json', '.jsonc': 'json',
  '.html': 'html', '.htm': 'html',
  '.css': 'css', '.scss': 'scss', '.less': 'less',
  '.md': 'markdown', '.markdown': 'markdown',
  '.py': 'python',
  '.yaml': 'yaml', '.yml': 'yaml',
  '.xml': 'xml',
  '.sh': 'shell', '.bash': 'shell', '.zsh': 'shell',
  '.sql': 'sql',
  '.go': 'go',
  '.rs': 'rust',
  '.java': 'java',
  '.c': 'c', '.h': 'c',
  '.cpp': 'cpp', '.hpp': 'cpp',
  '.toml': 'ini',
  '.ini': 'ini',
  '.vue': 'html',
  '.dockerfile': 'dockerfile',
  '.graphql': 'graphql',
  '.lua': 'lua',
  '.r': 'r',
  '.rb': 'ruby',
  '.php': 'php',
  '.swift': 'swift',
  '.kt': 'kotlin',
}

const IMAGE_EXTS = new Set(['.png', '.jpg', '.jpeg', '.gif', '.svg', '.webp', '.bmp', '.ico'])
const BINARY_EXTS = new Set([
  '.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp', '.ico',
  '.zip', '.gz', '.tar', '.7z', '.rar',
  '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx',
  '.mp3', '.mp4', '.wav', '.webm', '.avi', '.mov',
  '.exe', '.dll', '.so', '.dylib', '.bin', '.dat', '.db', '.sqlite',
])

function getFileExt(name: string): string {
  const idx = name.lastIndexOf('.')
  return idx >= 0 ? name.slice(idx).toLowerCase() : ''
}

export function getLanguageFromPath(filePath: string): string {
  const name = filePath.split('/').pop() || ''
  if (name === 'Dockerfile') return 'dockerfile'
  if (name === 'Makefile') return 'makefile'
  return EXT_LANG_MAP[getFileExt(name)] || 'plaintext'
}

export function isImageFile(name: string): boolean {
  return IMAGE_EXTS.has(getFileExt(name))
}

export function isMarkdownFile(name: string): boolean {
  const ext = getFileExt(name)
  return ext === '.md' || ext === '.markdown'
}

export function isTextFile(name: string): boolean {
  return !BINARY_EXTS.has(getFileExt(name))
}
