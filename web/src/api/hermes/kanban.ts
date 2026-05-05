import { request } from '../client'

export const kanbanStatuses = ['triage', 'todo', 'ready', 'running', 'blocked', 'done', 'archived'] as const
export type KanbanStatus = typeof kanbanStatuses[number]

export interface KanbanBoard {
  id: string
  slug: string
  name: string
  description?: string | null
  color?: string | null
  current: boolean
  counts?: Record<string, number>
}

export interface KanbanComment {
  id: string
  task_id: string
  author: string
  body: string
  created_at: string
}

export interface KanbanTask {
  id: string
  task_id: string
  board: string
  title: string
  body?: string | null
  assignee?: string | null
  status: KanbanStatus
  priority: number
  tenant?: string | null
  workspace_kind?: string | null
  workspace_path?: string | null
  created_by?: string | null
  result?: string | null
  created_at: string
  updated_at: string
  started_at?: string | null
  completed_at?: string | null
  comments?: KanbanComment[]
}

export interface CreateKanbanTaskRequest {
  board?: string
  title: string
  body?: string
  assignee?: string
  status?: KanbanStatus
  priority?: number
  tenant?: string
}

export async function fetchKanbanBoards(): Promise<KanbanBoard[]> {
  return request<KanbanBoard[]>('/api/kanban/boards')
}

export async function createKanbanBoard(data: { slug: string; name?: string; description?: string; switch?: boolean }) {
  return request<KanbanBoard>('/api/kanban/boards', {
    method: 'POST',
    body: JSON.stringify(data),
  })
}

export async function switchKanbanBoard(slug: string) {
  return request<KanbanBoard>(`/api/kanban/boards/${encodeURIComponent(slug)}/switch`, {
    method: 'POST',
  })
}

export async function fetchKanbanTasks(board?: string): Promise<KanbanTask[]> {
  const query = board ? `?board=${encodeURIComponent(board)}` : ''
  return request<KanbanTask[]>(`/api/kanban/tasks${query}`)
}

export async function fetchKanbanTask(taskId: string): Promise<KanbanTask> {
  return request<KanbanTask>(`/api/kanban/tasks/${encodeURIComponent(taskId)}`)
}

export async function createKanbanTask(data: CreateKanbanTaskRequest): Promise<KanbanTask> {
  return request<KanbanTask>('/api/kanban/tasks', {
    method: 'POST',
    body: JSON.stringify(data),
  })
}

export async function updateKanbanTask(taskId: string, data: Partial<CreateKanbanTaskRequest & { result: string }>): Promise<KanbanTask> {
  return request<KanbanTask>(`/api/kanban/tasks/${encodeURIComponent(taskId)}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  })
}

export async function moveKanbanTask(taskId: string, status: KanbanStatus): Promise<KanbanTask> {
  return request<KanbanTask>(`/api/kanban/tasks/${encodeURIComponent(taskId)}/status`, {
    method: 'POST',
    body: JSON.stringify({ status }),
  })
}

export async function addKanbanComment(taskId: string, body: string, author = 'dashboard'): Promise<KanbanTask> {
  return request<KanbanTask>(`/api/kanban/tasks/${encodeURIComponent(taskId)}/comments`, {
    method: 'POST',
    body: JSON.stringify({ author, body }),
  })
}
