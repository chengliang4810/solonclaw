import type { FileEntry } from '@/api/solonclaw/files'
import { getFileExt } from './fileTypes.ts'

export type FileIcon = '📁' | '⚙️' | '📋' | '📝' | '📄' | '🐍' | '📜' | '💚' | '🖼️' | '📦' | '⚡'

const FILE_ICON_BY_EXT: Readonly<Record<string, FileIcon>> = {
  '.yaml': '⚙️',
  '.yml': '⚙️',
  '.json': '📋',
  '.toml': '⚙️',
  '.md': '📝',
  '.txt': '📄',
  '.log': '📄',
  '.py': '🐍',
  '.js': '📜',
  '.ts': '📜',
  '.vue': '💚',
  '.png': '🖼️',
  '.jpg': '🖼️',
  '.jpeg': '🖼️',
  '.gif': '🖼️',
  '.svg': '🖼️',
  '.webp': '🖼️',
  '.zip': '📦',
  '.gz': '📦',
  '.tar': '📦',
  '.sh': '⚡',
  '.bash': '⚡',
} as const

export function fileTypeIcon(entry: FileEntry): FileIcon {
  if (entry.isDir) return '📁'
  return FILE_ICON_BY_EXT[getFileExt(entry.name)] ?? '📄'
}
