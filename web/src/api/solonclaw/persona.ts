import { request } from '../client'
import { getPersonaMeta } from '@/shared/personaMeta'
import { fetchWorkspaceFile } from './files'

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
  await request(`/api/workspace/files/${encodeURIComponent(key)}`, {
    method: 'PUT',
    body: JSON.stringify({ content }),
  })
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
