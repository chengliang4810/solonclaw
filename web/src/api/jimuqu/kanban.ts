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
  archived?: boolean
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
  finished?: boolean
  running?: boolean
  duration_ms?: number | null
  timed_out?: boolean
  outcome?: string | null
  summary?: string | null
  metadata?: unknown
  error?: string | null
}

export interface KanbanNotification {
  id: string
  task_id: string
  platform: string
  chat_id: string
  thread_id?: string | null
  user_id?: string | null
  created_at: string
}

export interface KanbanHomeNotificationChannel {
  platform: string
  chat_id: string
  thread_id?: string | null
  chat_name?: string | null
  updated_at?: number | string | null
  subscribed: boolean
}

export interface KanbanNotificationDeliveryResult {
  subscriptions: number
  claimed_events: number
  delivered_events: number
  failed_events: number
  removed_subscriptions: number
  errors?: string[]
}

export interface KanbanNotificationDeliveryStatus {
  available: boolean
  enabled: boolean
  running: boolean
  started_at?: number | null
  tick_seconds?: number | null
  last_tick_at?: number | null
  last_success_at?: number | null
  last_failure_at?: number | null
  last_error?: string | null
  last_result?: KanbanNotificationDeliveryResult | null
}

export interface KanbanTaskLog {
  task_id: string
  path: string
  exists: boolean
  tail_bytes?: number
  size?: number
  content?: string | null
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

export interface KanbanRunSummary {
  run_id?: string | null
  step_key?: string | null
  status?: string | null
  outcome?: string | null
  worker_id?: string | null
  started_at?: string | null
  ended_at?: string | null
  duration_ms?: number | null
  timed_out?: boolean | null
  summary?: string | null
  error?: string | null
}

export interface KanbanPipelineOverview {
  workflow_template_id?: string | null
  current_step_key?: string | null
  status: string
  stage: string
  assignee?: string | null
  worker_id?: string | null
  claim_lock?: string | null
  claim_expires_at?: string | null
  attempt_count: number
  retry_count?: number | null
  event_count: number
  warning_count: number
  next_action: string
  active_run?: KanbanRunSummary | null
  latest_run?: KanbanRunSummary | null
  supports_history: boolean
  supports_retry: boolean
  supports_reassign: boolean
  supports_reclaim: boolean
  supports_unblock: boolean
  supports_comment: boolean
  schema_task: boolean
}

export interface KanbanTaskDrawer {
  task_id: string
  task: KanbanTask
  runs: KanbanRun[]
  events: KanbanEvent[]
  execution_overview?: {
    stage: string
    status: string
    attempt_count: number
    retry_count?: number | null
    warning_count: number
    event_count: number
    active: boolean
    current_run_id?: string | null
    latest_run_id?: string | null
    latest_outcome?: string | null
    latest_summary?: string | null
    latest_error?: string | null
    last_worker?: string | null
    last_started_at?: string | null
    last_ended_at?: string | null
    last_duration_ms?: number | null
    last_timed_out?: boolean | null
    last_heartbeat_at?: string | null
    last_event_kind?: string | null
    last_event_at?: string | null
    last_event_summary?: string | null
    next_action: string
  }
  pipeline_overview?: KanbanPipelineOverview
  context: {
    task_id: string
    worker_context?: string
    task: KanbanTask
  }
  notifications: KanbanNotification[]
  log: KanbanTaskLog
  actions: {
    can_comment: boolean
    can_reassign: boolean
    can_reclaim: boolean
    can_retry: boolean
    can_unblock: boolean
    can_edit_result: boolean
  }
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

export interface StepKanbanTaskRequest {
  step_key: string
  workflow_template_id?: string
  note?: string
  actor?: string
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

export interface KanbanGuideStep {
  order: number
  title: string
  description: string
  commands: string[]
}

export interface KanbanGuide {
  board: KanbanBoard
  status_flow: KanbanStatus[]
  objective: string
  steps: KanbanGuideStep[]
  drawer_sections: string[]
  recovery_actions: string[]
  automation_actions: string[]
  stats: {
    by_status?: Record<string, number>
    by_assignee?: Record<string, Record<string, number>>
    oldest_ready_age_seconds?: number | null
    total?: number
  }
}

export async function fetchKanbanBoards(): Promise<KanbanBoard[]> {
  return request<KanbanBoard[]>('/api/kanban/boards')
}

export async function fetchKanbanBoardsWithArchived(includeArchived = false): Promise<KanbanBoard[]> {
  const suffix = includeArchived ? '?archived=true' : ''
  return request<KanbanBoard[]>(`/api/kanban/boards${suffix}`)
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

export async function renameKanbanBoard(slug: string, name: string): Promise<KanbanBoard> {
  return request<KanbanBoard>(`/api/kanban/boards/${encodeURIComponent(slug)}`, {
    method: 'PUT',
    body: JSON.stringify({ name }),
  })
}

export async function removeKanbanBoard(slug: string, hardDelete = false): Promise<{ slug: string; action: string; current: KanbanBoard }> {
  const suffix = hardDelete ? '?delete=true' : ''
  return request<{ slug: string; action: string; current: KanbanBoard }>(`/api/kanban/boards/${encodeURIComponent(slug)}${suffix}`, {
    method: 'DELETE',
  })
}

export interface KanbanTaskQuery {
  board?: string
  status?: KanbanStatus
  assignee?: string
  tenant?: string
  archived?: boolean
}

export async function fetchKanbanTasks(query: string | KanbanTaskQuery = ''): Promise<KanbanTask[]> {
  const params = new URLSearchParams()
  if (typeof query === 'string') {
    if (query) params.set('board', query)
  } else {
    if (query.board) params.set('board', query.board)
    if (query.status) params.set('status', query.status)
    if (query.assignee) params.set('assignee', query.assignee)
    if (query.tenant) params.set('tenant', query.tenant)
    if (query.archived) params.set('archived', 'true')
  }
  const suffix = params.toString() ? `?${params.toString()}` : ''
  return request<KanbanTask[]>(`/api/kanban/tasks${suffix}`)
}

export async function fetchKanbanGuide(board?: string): Promise<KanbanGuide> {
  const params = new URLSearchParams()
  if (board) params.set('board', board)
  const suffix = params.toString() ? `?${params.toString()}` : ''
  return request<KanbanGuide>(`/api/kanban/guide${suffix}`)
}

export async function fetchKanbanTask(taskId: string): Promise<KanbanTask> {
  return request<KanbanTask>(`/api/kanban/tasks/${encodeURIComponent(taskId)}`)
}

export async function fetchKanbanTaskDrawer(taskId: string, tail = 4096): Promise<KanbanTaskDrawer> {
  const params = new URLSearchParams()
  params.set('tail', String(tail))
  return request<KanbanTaskDrawer>(`/api/kanban/tasks/${encodeURIComponent(taskId)}/drawer?${params.toString()}`)
}

export async function fetchKanbanHomeNotificationChannels(taskId: string): Promise<KanbanHomeNotificationChannel[]> {
  const params = new URLSearchParams()
  params.set('task', taskId)
  return request<KanbanHomeNotificationChannel[]>(`/api/kanban/notify-subscriptions/home-channels?${params.toString()}`)
}

export async function subscribeKanbanHomeNotification(taskId: string, platform: string): Promise<KanbanNotification> {
  return request<KanbanNotification>(`/api/kanban/tasks/${encodeURIComponent(taskId)}/home-subscribe/${encodeURIComponent(platform)}`, {
    method: 'POST',
  })
}

export async function unsubscribeKanbanHomeNotification(taskId: string, platform: string): Promise<{ removed: boolean }> {
  return request<{ removed: boolean }>(`/api/kanban/tasks/${encodeURIComponent(taskId)}/home-subscribe/${encodeURIComponent(platform)}`, {
    method: 'DELETE',
  })
}

export async function unsubscribeKanbanNotification(notification: KanbanNotification): Promise<{ removed: boolean }> {
  return request<{ removed: boolean }>('/api/kanban/notify-subscriptions/remove', {
    method: 'POST',
    body: JSON.stringify({
      task_id: notification.task_id,
      platform: notification.platform,
      chat_id: notification.chat_id,
      thread_id: notification.thread_id || '',
    }),
  })
}

export async function deliverKanbanNotifications(): Promise<KanbanNotificationDeliveryResult> {
  return request<KanbanNotificationDeliveryResult>('/api/kanban/notify-subscriptions/deliver', {
    method: 'POST',
  })
}

export async function fetchKanbanNotificationDeliveryStatus(): Promise<KanbanNotificationDeliveryStatus> {
  return request<KanbanNotificationDeliveryStatus>('/api/kanban/notify-subscriptions/delivery-status')
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

export async function stepKanbanTask(taskId: string, data: StepKanbanTaskRequest): Promise<KanbanTask> {
  return request<KanbanTask>(`/api/kanban/tasks/${encodeURIComponent(taskId)}/step`, {
    method: 'POST',
    body: JSON.stringify({ ...data, actor: data.actor || 'dashboard' }),
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
