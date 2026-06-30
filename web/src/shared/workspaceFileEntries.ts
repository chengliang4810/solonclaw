import type { FileEntry, WorkspaceFile } from '@/api/solonclaw/files'

export function normalizeWorkspaceBrowserPath(path: string): string {
  const value = (path || '').replace(/\\/g, '/').replace(/^workspace:\/\/files\//, '')
  return value.split('/').filter(Boolean).join('/')
}

export function workspaceFileEntries(files: WorkspaceFile[], path: string): { entries: FileEntry[]; path: string } {
  const currentPath = normalizeWorkspaceBrowserPath(path)
  const prefix = currentPath ? `${currentPath}/` : ''
  const dirs = new Set<string>()
  const entries: FileEntry[] = []

  for (const file of files) {
    const relativePath = normalizeWorkspaceBrowserPath(file.name)
    if (!relativePath || (currentPath && !relativePath.startsWith(prefix))) {
      continue
    }

    const visiblePath = currentPath ? relativePath.slice(prefix.length) : relativePath
    const [name = '', ...rest] = visiblePath.split('/')
    if (!name) {
      continue
    }

    if (rest.length > 0) {
      dirs.add(name)
      continue
    }

    entries.push({
      name,
      path: relativePath,
      isDir: false,
      size: file.content.length,
      modTime: '',
    })
  }

  const directoryEntries = [...dirs].sort((a, b) => a.localeCompare(b)).map(name => ({
    name,
    path: currentPath ? `${currentPath}/${name}` : name,
    isDir: true,
    size: 0,
    modTime: '',
  }))

  return {
    path: currentPath,
    entries: [...directoryEntries, ...entries],
  }
}
