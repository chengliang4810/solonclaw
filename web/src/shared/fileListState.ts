import type { FileEntry } from '@/api/solonclaw/files'

export interface FileListErrorState {
  entries: FileEntry[]
  error: string
}

export function fileListLoadFailedState(err: unknown): FileListErrorState {
  return {
    entries: [],
    error: err instanceof Error ? err.message : String(err || ''),
  }
}
