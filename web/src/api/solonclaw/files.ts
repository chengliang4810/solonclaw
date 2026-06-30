import { getApiKey, getBaseUrlValue, request } from '../client'
import { normalizeWorkspaceBrowserPath, workspaceFileEntries } from '@/shared/workspaceFileEntries'

export interface FileEntry {
  name: string
  path: string
  isDir: boolean
  size: number
  modTime: string
}

export interface FileStat {
  name: string
  path: string
  isDir: boolean
  size: number
  modTime: string
  permissions?: string
}

export interface WorkspaceFile {
  key: string
  name: string
  path: string
  exists: boolean
  content: string
}

const PATH_TO_KEY: Record<string, string> = {
  'AGENTS.md': 'agents',
  'SOUL.md': 'soul',
  'USER.md': 'user',
  'TOOLS.md': 'tools',
  'HEARTBEAT.md': 'heartbeat',
  'MEMORY.md': 'memory',
}

function keyForPath(path: string): string {
  const normalized = normalizeWorkspaceBrowserPath(path)
  if (PATH_TO_KEY[normalized]) return PATH_TO_KEY[normalized]
  if (normalized.startsWith('memory/')) return 'memory_today'
  return Object.entries(PATH_TO_KEY).find(([, key]) => key === normalized)?.[1] || normalized
}

async function workspaceFiles(): Promise<WorkspaceFile[]> {
  const res = await request<{ files: WorkspaceFile[] }>('/api/workspace/files')
  return res.files || []
}

export async function fetchWorkspaceFile(key: string): Promise<WorkspaceFile> {
  return request<WorkspaceFile>(`/api/workspace/files/${encodeURIComponent(key)}`)
}

export async function saveWorkspaceFile(key: string, content: string): Promise<void> {
  await request(`/api/workspace/files/${encodeURIComponent(key)}`, {
    method: 'PUT',
    body: JSON.stringify({ content }),
  })
}

export async function restoreWorkspaceFile(key: string): Promise<WorkspaceFile> {
  const res = await request<{ file: WorkspaceFile }>(`/api/workspace/files/${encodeURIComponent(key)}/restore`, {
    method: 'POST',
  })
  return res.file
}

export async function listFiles(path: string = ''): Promise<{ entries: FileEntry[]; path: string }> {
  const files = await workspaceFiles()
  return workspaceFileEntries(files, path)
}

export async function statFile(path: string): Promise<FileStat> {
  const files = await workspaceFiles()
  const file = files.find((item) => item.name === path || item.path === path)
  if (!file) throw new Error('File not found')
  return {
    name: file.name,
    path: file.name,
    isDir: false,
    size: file.content.length,
    modTime: '',
  }
}

export async function readFile(path: string): Promise<{ content: string; path: string; size: number }> {
  const key = keyForPath(path)
  const file = await fetchWorkspaceFile(key)
  return {
    content: file.content || '',
    path,
    size: (file.content || '').length,
  }
}

export async function writeFile(path: string, content: string): Promise<void> {
  const key = keyForPath(path)
  await saveWorkspaceFile(key, content)
}

export async function restoreFile(path: string): Promise<WorkspaceFile> {
  const key = keyForPath(path)
  return restoreWorkspaceFile(key)
}

export function getFileDownloadUrl(relativePath: string, fileName?: string): string {
  const base = getBaseUrlValue()
  const params = new URLSearchParams({ path: relativePath })
  if (fileName) params.set('name', fileName)
  const token = getApiKey()
  if (token) params.set('token', token)
  return `${base}/api/solonclaw/download?${params.toString()}`
}
