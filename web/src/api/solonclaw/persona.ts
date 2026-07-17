import { request } from '../client'
import { fetchWorkspaceFile, saveWorkspaceFile } from './files'
import { getPersonaMeta } from '@/shared/personaMeta'

export interface PersonaFileData {
  key: string
  title: string
  fileName: string
  path: string
  exists: boolean
  content: string
}

export interface PersonaDiaryEntry {
  name: string
  relativePath: string
  path: string
  kind: 'active' | 'archive' | 'summary'
}

export interface MemoryArchiveState {
  lastStartedAt: number
  lastCompletedAt: number
  lastOutcome: string
  selectedCount: number
  archivedCount: number
  summarizedByAiCount: number
  summarizedByFallbackCount: number
  memoryCandidateCount: number
  failedCount: number
  lastError: string
  lastFallbackReason: string
  durationMs: number
}

export function personaMeta(key: string) {
  return getPersonaMeta(key)
}

export async function fetchPersonaFile(key: string): Promise<PersonaFileData> {
  const file = await fetchWorkspaceFile(key)
  const meta = personaMeta(key)
  return {
    key,
    title: meta.title,
    fileName: key === 'memory_today' ? file.name : meta.fileName,
    path: file.path,
    exists: file.exists,
    content: file.content || '',
  }
}

export async function savePersonaFile(key: string, content: string): Promise<void> {
  await saveWorkspaceFile(key, content)
}

export async function fetchPersonaDiaries(): Promise<PersonaDiaryEntry[]> {
  const res = await request<{ files: PersonaDiaryEntry[] }>('/api/workspace/diaries')
  return res.files || []
}

export async function fetchPersonaDiary(relativePath: string): Promise<{
  name: string
  relativePath: string
  path: string
  content: string
}> {
  return request(`/api/workspace/diaries/read?path=${encodeURIComponent(relativePath)}`)
}

export async function fetchMemoryArchiveState(): Promise<MemoryArchiveState> {
  return request('/api/workspace/memory/archive')
}

export async function runMemoryArchive(): Promise<MemoryArchiveState> {
  return request('/api/workspace/memory/archive/run', { method: 'POST' })
}

export async function restoreMemoryArchive(path: string): Promise<{ message: string }> {
  return request('/api/workspace/memory/archive/restore', {
    method: 'POST',
    body: JSON.stringify({ path }),
  })
}
