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

export interface KanbanEvent {
  id: string
  task_id: string
  kind: string
  payload?: unknown
  created_at: string
}

export interface KanbanRun {
  id: string
  run_id: string
  task_id: string
  profile?: string | null
  step_key?: string | null
  status: string
  claim_lock?: string | null
  claim_expires_at?: string | null
  worker_pid?: number | null
  worker_id?: string | null
  max_runtime_seconds?: number | null
  last_heartbeat_at?: string | null
  started_at?: string | null
  ended_at?: string | null
  outcome?: string | null
  summary?: string | null
  metadata?: unknown
  error?: string | null
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
  idempotency_key?: string | null
  claim_lock?: string | null
  claim_expires_at?: string | null
  worker_id?: string | null
  worker_pid?: number | null
  last_spawn_error?: string | null
  spawn_failures?: number
  max_retries?: number | null
  max_runtime_seconds?: number | null
  last_heartbeat_at?: string | null
  current_run_id?: string | null
  workflow_template_id?: string | null
  current_step_key?: string | null
  skills?: unknown
  created_at: string
  updated_at: string
  started_at?: string | null
  completed_at?: string | null
  comments?: KanbanComment[]
  events?: KanbanEvent[]
  warnings?: KanbanEvent[]
  runs?: KanbanRun[]
  active_run?: KanbanRun | null
  latest_run?: KanbanRun | null
  retry_count?: number
  parents?: Array<Pick<KanbanTask, 'id' | 'task_id' | 'title' | 'status' | 'assignee' | 'priority'>>
  children?: Array<Pick<KanbanTask, 'id' | 'task_id' | 'title' | 'status' | 'assignee' | 'priority'>>
  worker_context?: string
}

export interface CreateKanbanTaskRequest {
  board?: string
  title: string
  body?: string
  assignee?: string
  status?: KanbanStatus
  priority?: number
  tenant?: string
  idempotency_key?: string
  parents?: string[]
  max_retries?: number | null
  max_runtime_seconds?: number
  skills?: string[]
  workflow_template_id?: string
  current_step_key?: string
}

export interface KanbanDispatchResult {
  reclaimed: number
  promoted: number
  timed_out: number
  spawned: Array<{ task_id: string; assignee?: string; workspace_path?: string; worker_pid?: number | null }>
  skipped_unassigned: string[]
  skipped_nonspawnable: string[]
  spawn_failures: string[]
  auto_blocked: string[]
}

export interface KanbanDaemonStatus {
  running: boolean
  board?: string | null
  max_spawn: number
  failure_limit: number
  ttl_seconds: number
  interval_seconds: number
  dry_run: boolean
  started_at?: number | null
  last_tick_at?: number | null
  tick_count: number
  last_result?: KanbanDispatchResult | null
  last_error?: string | null
  started?: boolean
  already_running?: boolean
  stopping?: boolean
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

export async function updateKanbanTask(
  taskId: string,
  data: Partial<CreateKanbanTaskRequest & { result: string; claim_lock: string; claim_expires_at: number; worker_id: string }>,
): Promise<KanbanTask> {
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

export async function reclaimKanbanTask(taskId: string, reason?: string): Promise<KanbanTask> {
  return request<KanbanTask>(`/api/kanban/tasks/${encodeURIComponent(taskId)}/reclaim`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  })
}

export async function reassignKanbanTask(
  taskId: string,
  assignee: string,
  reclaimFirst = false,
  reason?: string,
): Promise<KanbanTask> {
  return request<KanbanTask>(`/api/kanban/tasks/${encodeURIComponent(taskId)}/reassign`, {
    method: 'POST',
    body: JSON.stringify({ assignee, reclaim_first: reclaimFirst, reason }),
  })
}

export async function retryKanbanTask(taskId: string, reason?: string): Promise<KanbanTask> {
  return request<KanbanTask>(`/api/kanban/tasks/${encodeURIComponent(taskId)}/retry`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  })
}

export async function dispatchKanban(data: {
  board?: string
  max_spawn?: number
  dry_run?: boolean
  ttl_seconds?: number
  failure_limit?: number
}): Promise<KanbanDispatchResult> {
  return request<KanbanDispatchResult>('/api/kanban/dispatch', {
    method: 'POST',
    body: JSON.stringify(data),
  })
}

export async function fetchKanbanDaemon(): Promise<KanbanDaemonStatus> {
  return request<KanbanDaemonStatus>('/api/kanban/daemon')
}

export async function startKanbanDaemon(data: {
  board?: string
  max_spawn?: number
  interval_seconds?: number
  ttl_seconds?: number
  failure_limit?: number
  dry_run?: boolean
}): Promise<KanbanDaemonStatus> {
  return request<KanbanDaemonStatus>('/api/kanban/daemon/start', {
    method: 'POST',
    body: JSON.stringify(data),
  })
}

export async function stopKanbanDaemon(): Promise<KanbanDaemonStatus> {
  return request<KanbanDaemonStatus>('/api/kanban/daemon/stop', {
    method: 'POST',
  })
}
