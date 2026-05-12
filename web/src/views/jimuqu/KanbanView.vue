<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NInput, NInputNumber, NModal, NSelect, NSpin, useMessage } from 'naive-ui'
import {
  addKanbanComment,
  createKanbanBoard,
  createKanbanTask,
  dispatchKanban,
  fetchKanbanBoardsWithArchived,
  fetchKanbanDaemon,
  fetchKanbanGuide,
  fetchKanbanHomeNotificationChannels,
  fetchKanbanTaskDrawer,
  fetchKanbanTasks,
  kanbanStatuses,
  moveKanbanTask,
  reclaimKanbanTask,
  removeKanbanBoard,
  renameKanbanBoard,
  reassignKanbanTask,
  retryKanbanTask,
  startKanbanDaemon,
  subscribeKanbanHomeNotification,
  stepKanbanTask,
  stopKanbanDaemon,
  switchKanbanBoard,
  unsubscribeKanbanHomeNotification,
  updateKanbanTask,
  type KanbanBoard,
  type KanbanDaemonStatus,
  type KanbanEvent,
  type KanbanGuide,
  type KanbanHomeNotificationChannel,
  type KanbanNotification,
  type KanbanPipelineOverview,
  type KanbanRun,
  type KanbanRunSummary,
  type KanbanStatus,
  type KanbanTask,
  type KanbanTaskDrawer,
} from '@/api/jimuqu/kanban'

const message = useMessage()
const loading = ref(false)
const boards = ref<KanbanBoard[]>([])
const tasks = ref<KanbanTask[]>([])
const activeBoard = ref('')
const showTaskModal = ref(false)
const showBoardModal = ref(false)
const showBoardManager = ref(false)
const selectedTask = ref<KanbanTask | null>(null)
const selectedDrawer = ref<KanbanTaskDrawer | null>(null)
const homeNotificationChannels = ref<KanbanHomeNotificationChannel[]>([])
const notificationBusy = ref('')
const commentText = ref('')
const recoveryReason = ref('')
const reassignAssignee = ref('')
const stepForm = ref({ workflow_template_id: '', step_key: '', note: '' })
const dispatching = ref(false)
const daemonBusy = ref(false)
const daemon = ref<KanbanDaemonStatus | null>(null)
const guide = ref<KanbanGuide | null>(null)
const showGuide = ref(false)
const daemonInterval = ref(60)
const daemonMaxSpawn = ref(3)
const tenantFilter = ref<string | null>('')
const assigneeFilter = ref<string | null>('')
const search = ref('')
const showArchivedBoards = ref(false)
const boardRenames = ref<Record<string, string>>({})
const boardBusy = ref('')
const taskForm = ref({
  title: '',
  body: '',
  assignee: '',
  status: 'todo' as KanbanStatus,
  priority: 0,
  max_retries: null as number | null,
  tenant: '',
  parents: '',
  skills: '',
  workflow_template_id: '',
  current_step_key: '',
})
const boardForm = ref({ slug: '', name: '', description: '' })

const statusLabels: Record<KanbanStatus, string> = {
  triage: '待梳理',
  todo: '待办',
  ready: '就绪',
  running: '执行中',
  blocked: '阻塞',
  done: '完成',
  archived: '归档',
}

const statusOptions = kanbanStatuses.map(status => ({ label: statusLabels[status], value: status }))
const visibleStatuses = kanbanStatuses.filter(status => status !== 'archived')

const boardOptions = computed(() => boards.value.map(board => ({
  label: boardLabel(board),
  value: board.slug,
})))

const tenantOptions = computed(() => {
  const values = new Set<string>()
  tasks.value.forEach(task => {
    const tenant = task.tenant?.trim()
    if (tenant) values.add(tenant)
  })
  return Array.from(values).sort().map(value => ({ label: value, value }))
})

const assigneeOptions = computed(() => {
  const values = new Set<string>()
  tasks.value.forEach(task => {
    const assignee = task.assignee?.trim()
    if (assignee) values.add(assignee)
  })
  return Array.from(values).sort().map(value => ({ label: value, value }))
})

const filteredTasks = computed(() => {
  const tenant = filterText(tenantFilter.value)
  const assignee = filterText(assigneeFilter.value)
  const q = search.value.trim().toLowerCase()
  return tasks.value.filter(task => {
    if (tenant && task.tenant !== tenant) return false
    if (assignee && task.assignee !== assignee) return false
    if (q) {
      const hay = `${task.id} ${task.title || ''} ${task.assignee || ''} ${task.tenant || ''}`.toLowerCase()
      if (!hay.includes(q)) return false
    }
    return true
  })
})

const taskColumns = computed(() => {
  const grouped = new Map<KanbanStatus, KanbanTask[]>()
  visibleStatuses.forEach(status => grouped.set(status, []))
  filteredTasks.value.forEach(task => {
    if (grouped.has(task.status)) {
      grouped.get(task.status)!.push(task)
    }
  })
  return visibleStatuses.map(status => ({
    status,
    title: statusLabels[status],
    tasks: grouped.get(status) || [],
  }))
})

onMounted(loadKanban)

async function loadKanban() {
  loading.value = true
  try {
    boards.value = await fetchKanbanBoardsWithArchived(showArchivedBoards.value)
    syncBoardRenames()
    activeBoard.value = boards.value.find(board => board.current)?.slug || boards.value[0]?.slug || ''
    tasks.value = await fetchKanbanTasks(taskQuery())
    daemon.value = await fetchKanbanDaemon()
    guide.value = activeBoard.value ? await fetchKanbanGuide(activeBoard.value) : null
  } finally {
    loading.value = false
  }
}

async function reloadTasks() {
  boards.value = await fetchKanbanBoardsWithArchived(showArchivedBoards.value)
  syncBoardRenames()
  if (!boards.value.some(board => board.slug === activeBoard.value && !isArchivedBoard(board))) {
    activeBoard.value = boards.value.find(board => board.current && !isArchivedBoard(board))?.slug
      || boards.value.find(board => !isArchivedBoard(board))?.slug
      || ''
  }
  tasks.value = activeBoard.value ? await fetchKanbanTasks(taskQuery()) : []
  daemon.value = await fetchKanbanDaemon()
  guide.value = activeBoard.value ? await fetchKanbanGuide(activeBoard.value) : null
}

function taskQuery() {
  return {
    board: activeBoard.value,
    tenant: filterText(tenantFilter.value),
    assignee: filterText(assigneeFilter.value),
  }
}

function filterText(value: string | null | undefined) {
  return (value || '').trim()
}

function splitList(value: string): string[] {
  return value
    .split(/[\s,，]+/)
    .map(item => item.trim())
    .filter(Boolean)
}

function taskRefText(tasks?: Array<Pick<KanbanTask, 'id' | 'task_id' | 'title' | 'status' | 'assignee' | 'priority'>>): string {
  return (tasks || [])
    .map(task => task.task_id || task.id)
    .filter(Boolean)
    .join(', ')
}

function skillsText(skills: unknown): string {
  if (!skills) return ''
  if (Array.isArray(skills)) {
    return skills.map(item => String(item)).filter(Boolean).join(', ')
  }
  if (typeof skills === 'string') return skills
  return ''
}

function taskPipelineLabel(task: KanbanTask): string {
  const values = []
  if (task.workflow_template_id) values.push(task.workflow_template_id)
  if (task.current_step_key) values.push(task.current_step_key)
  return values.join(' / ')
}

function taskRefLabel(task: Pick<KanbanTask, 'id' | 'task_id' | 'title' | 'status' | 'assignee' | 'priority'>): string {
  const owner = task.assignee ? ` @${task.assignee}` : ''
  return `${task.task_id || task.id} · ${task.title || '-'} · ${statusLabels[task.status as KanbanStatus] || task.status || '-'}${owner}`
}

async function handleFilterChange() {
  await reloadTasks()
}

async function handleBoardChange(slug: string) {
  activeBoard.value = slug
  await switchKanbanBoard(slug)
  await reloadTasks()
}

function openCreateTask() {
  selectedTask.value = null
  taskForm.value = {
    title: '',
    body: '',
    assignee: '',
    status: 'todo',
    priority: 0,
    max_retries: null,
    tenant: '',
    parents: '',
    skills: '',
    workflow_template_id: '',
    current_step_key: '',
  }
  showTaskModal.value = true
}

async function openTask(task: KanbanTask) {
  selectedDrawer.value = await fetchKanbanTaskDrawer(task.id)
  selectedTask.value = selectedDrawer.value.task
  homeNotificationChannels.value = await fetchKanbanHomeNotificationChannels(selectedTask.value.id)
  taskForm.value = {
    title: selectedTask.value.title,
    body: selectedTask.value.body || '',
    assignee: selectedTask.value.assignee || '',
    status: selectedTask.value.status,
    priority: selectedTask.value.priority || 0,
    max_retries: selectedTask.value.max_retries || null,
    tenant: selectedTask.value.tenant || '',
    parents: taskRefText(selectedTask.value.parents),
    skills: skillsText(selectedTask.value.skills),
    workflow_template_id: selectedTask.value.workflow_template_id || '',
    current_step_key: selectedTask.value.current_step_key || '',
  }
  commentText.value = ''
  recoveryReason.value = ''
  reassignAssignee.value = selectedTask.value.assignee || ''
  stepForm.value = {
    workflow_template_id: selectedTask.value.workflow_template_id || '',
    step_key: selectedTask.value.current_step_key || '',
    note: '',
  }
  showTaskModal.value = true
}

async function refreshSelectedTaskDrawer() {
  if (!selectedTask.value) return
  selectedDrawer.value = await fetchKanbanTaskDrawer(selectedTask.value.id)
  selectedTask.value = selectedDrawer.value.task
  homeNotificationChannels.value = await fetchKanbanHomeNotificationChannels(selectedTask.value.id)
}

async function saveTask() {
  if (!taskForm.value.title.trim()) {
    message.error('任务标题不能为空')
    return
  }
  const payload = {
    board: activeBoard.value,
    title: taskForm.value.title.trim(),
    body: taskForm.value.body.trim(),
    assignee: taskForm.value.assignee.trim(),
    status: taskForm.value.status,
    priority: taskForm.value.priority || 0,
    max_retries: taskForm.value.max_retries || null,
    tenant: taskForm.value.tenant.trim(),
    parents: splitList(taskForm.value.parents),
    skills: splitList(taskForm.value.skills),
    workflow_template_id: taskForm.value.workflow_template_id.trim(),
    current_step_key: taskForm.value.current_step_key.trim(),
  }
  if (selectedTask.value) {
    await updateKanbanTask(selectedTask.value.id, payload)
    message.success('任务已更新')
    await refreshSelectedTaskDrawer()
  } else {
    await createKanbanTask(payload)
    message.success('任务已创建')
  }
  showTaskModal.value = false
  await reloadTasks()
}

async function moveTask(task: KanbanTask, status: KanbanStatus) {
  if (task.status === status) return
  await moveKanbanTask(task.id, status)
  await reloadTasks()
}

async function saveComment() {
  if (!selectedTask.value || !commentText.value.trim()) return
  selectedTask.value = await addKanbanComment(selectedTask.value.id, commentText.value.trim())
  await refreshSelectedTaskDrawer()
  commentText.value = ''
  await reloadTasks()
}

async function advanceSelectedStep() {
  if (!selectedTask.value) return
  if (!stepForm.value.step_key.trim()) {
    message.error('请输入目标步骤')
    return
  }
  try {
    selectedTask.value = await stepKanbanTask(selectedTask.value.id, {
      workflow_template_id: stepForm.value.workflow_template_id.trim(),
      step_key: stepForm.value.step_key.trim(),
      note: stepForm.value.note.trim(),
      actor: 'dashboard',
    })
    await refreshSelectedTaskDrawer()
    stepForm.value = {
      workflow_template_id: selectedTask.value.workflow_template_id || '',
      step_key: selectedTask.value.current_step_key || '',
      note: '',
    }
    taskForm.value.workflow_template_id = stepForm.value.workflow_template_id
    taskForm.value.current_step_key = stepForm.value.step_key
    message.success('流程步骤已推进')
    await reloadTasks()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '推进流程步骤失败')
  }
}

async function reclaimSelectedTask() {
  if (!selectedTask.value) return
  try {
    selectedTask.value = await reclaimKanbanTask(selectedTask.value.id, recoveryReason.value.trim() || 'dashboard')
    await refreshSelectedTaskDrawer()
    recoveryReason.value = ''
    message.success('任务执行权已收回')
    await reloadTasks()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '收回执行权失败')
  }
}

async function reassignSelectedTask(reclaimFirst = false) {
  if (!selectedTask.value || !reassignAssignee.value.trim()) {
    message.error('请输入新的执行人')
    return
  }
  try {
    selectedTask.value = await reassignKanbanTask(
      selectedTask.value.id,
      reassignAssignee.value.trim(),
      reclaimFirst,
      recoveryReason.value.trim() || 'dashboard',
    )
    await refreshSelectedTaskDrawer()
    recoveryReason.value = ''
    message.success('任务已重新分配')
    await reloadTasks()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '重新分配失败')
  }
}

async function retrySelectedTask() {
  if (!selectedTask.value) return
  try {
    selectedTask.value = await retryKanbanTask(selectedTask.value.id, recoveryReason.value.trim() || 'dashboard')
    await refreshSelectedTaskDrawer()
    recoveryReason.value = ''
    message.success('任务已重置为就绪')
    await reloadTasks()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '重试任务失败')
  }
}

async function runDispatcher(dryRun = false) {
  dispatching.value = true
  try {
    const result = await dispatchKanban({
      board: activeBoard.value,
      max_spawn: 3,
      dry_run: dryRun,
    })
    const spawned = result.spawned?.length || 0
    const skipped = result.skipped_unassigned?.length || 0
    const blocked = result.auto_blocked?.length || 0
    message.success(`派发完成：启动 ${spawned}，晋级 ${result.promoted}，收回 ${result.reclaimed + result.timed_out}，未分配 ${skipped}，自动阻塞 ${blocked}`)
    await reloadTasks()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '派发失败')
  } finally {
    dispatching.value = false
  }
}

async function startDaemon() {
  daemonBusy.value = true
  try {
    daemon.value = await startKanbanDaemon({
      board: activeBoard.value,
      max_spawn: daemonMaxSpawn.value,
      interval_seconds: daemonInterval.value,
      failure_limit: 3,
    })
    message.success(daemon.value.already_running ? '后台派发已在运行' : '后台派发已启动')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '启动后台派发失败')
  } finally {
    daemonBusy.value = false
  }
}

async function stopDaemon() {
  daemonBusy.value = true
  try {
    daemon.value = await stopKanbanDaemon()
    message.success('后台派发已停止')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '停止后台派发失败')
  } finally {
    daemonBusy.value = false
  }
}

async function saveBoard() {
  if (!boardForm.value.slug.trim()) {
    message.error('看板标识不能为空')
    return
  }
  const board = await createKanbanBoard({
    slug: boardForm.value.slug.trim(),
    name: boardForm.value.name.trim() || boardForm.value.slug.trim(),
    description: boardForm.value.description.trim(),
    switch: true,
  })
  showBoardModal.value = false
  boardForm.value = { slug: '', name: '', description: '' }
  activeBoard.value = board.slug
  await loadKanban()
}

function eventPayload(event: KanbanEvent): Record<string, unknown> {
  if (event.payload && typeof event.payload === 'object' && !Array.isArray(event.payload)) {
    return event.payload as Record<string, unknown>
  }
  return {}
}

function eventSummary(event: KanbanEvent): string {
  const payload = eventPayload(event)
  if (event.kind === 'completion_blocked_hallucination') {
    return `完成被阻止：${String(payload.failures || payload.created_cards || '')}`
  }
  if (event.kind === 'suspected_hallucinated_references') {
    return `疑似引用了不存在的卡片：${String(payload.suspected_ids || '')}`
  }
  if (event.kind === 'reclaimed') {
    return `已收回执行权：${String(payload.reason || '-')}`
  }
  if (event.kind === 'reassigned') {
    return `已重新分配给 ${String(payload.assignee || '-')}`
  }
  if (event.kind === 'completed') {
    return `完成记录：${String(payload.summary || '')}`
  }
  if (event.kind === 'step_changed') {
    const workflow = String(payload.to_workflow || payload.from_workflow || '-')
    const fromStep = String(payload.from_step || '-')
    const toStep = String(payload.to_step || '-')
    const note = payload.note ? `：${String(payload.note)}` : ''
    return `流程 ${workflow}：${fromStep} -> ${toStep}${note}`
  }
  return event.kind
}

function runSummary(run: KanbanRun): string {
  if (run.summary) return run.summary
  if (run.error) return run.error
  return run.outcome || run.status || '-'
}

function runStatusLabel(run: KanbanRun): string {
  if (run.timed_out) return '已超时'
  const value = run.outcome || run.status
  const labels: Record<string, string> = {
    running: '运行中',
    ok: '成功',
    success: '成功',
    done: '完成',
    failed: '失败',
    error: '错误',
    cancelled: '已取消',
    timeout: '超时',
    timed_out: '超时',
    pending: '等待',
  }
  return value ? labels[value] || value : '-'
}

function runSummaryStatusLabel(run?: KanbanRunSummary | null): string {
  if (!run) return '-'
  if (run.timed_out) return '已超时'
  const value = run.outcome || run.status
  if (!value) return '-'
  const labels: Record<string, string> = {
    running: '运行中',
    ok: '成功',
    success: '成功',
    done: '完成',
    completed: '完成',
    failed: '失败',
    error: '错误',
    cancelled: '已取消',
    reclaimed: '已收回',
    timeout: '超时',
    timed_out: '超时',
    pending: '等待',
  }
  return labels[value] || value
}

function runSummaryText(run?: KanbanRunSummary | null): string {
  if (!run) return '暂无'
  return run.summary || run.error || run.outcome || run.status || run.run_id || '-'
}

function runStateLabel(run: KanbanRun): string {
  if (run.timed_out) return '超时'
  if (run.running) return '运行中'
  if (run.finished) return '已结束'
  return '未结束'
}

function runTone(run: KanbanRun): Record<string, boolean> {
  const value = `${run.outcome || ''} ${run.status || ''}`.toLowerCase()
  return {
    active: Boolean(run.running),
    timeout: Boolean(run.timed_out || value.includes('timeout')),
    failed: Boolean(value.includes('fail') || value.includes('error')),
    success: Boolean(value.includes('ok') || value.includes('success') || value.includes('done')),
  }
}

function runMetadata(run: KanbanRun): Record<string, unknown> | null {
  if (run.metadata && typeof run.metadata === 'object' && !Array.isArray(run.metadata)) {
    return run.metadata as Record<string, unknown>
  }
  return null
}

function compactValue(value: unknown): string {
  if (value === null || value === undefined) return '-'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

function runMetadataSummary(run: KanbanRun): string {
  const metadata = runMetadata(run)
  if (!metadata) return ''
  const entries = Object.entries(metadata).filter(([, value]) => value !== null && value !== undefined)
  if (!entries.length) return ''
  return entries
    .slice(0, 6)
    .map(([key, value]) => `${key}=${compactValue(value)}`)
    .join(' / ')
}

function runTimingSummary(run: KanbanRun): string {
  const parts = []
  if (run.started_at) parts.push(`开始 ${run.started_at}`)
  if (run.ended_at) parts.push(`结束 ${run.ended_at}`)
  if (run.last_heartbeat_at) parts.push(`心跳 ${run.last_heartbeat_at}`)
  const duration = formatDuration(run.duration_ms)
  if (duration) parts.push(`耗时 ${duration}`)
  return parts.join(' / ')
}

function formatDuration(durationMs?: number | null): string {
  if (durationMs === null || durationMs === undefined) return ''
  const ms = Math.max(0, Number(durationMs) || 0)
  if (ms < 1000) return `${ms}ms`
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  const remainSeconds = seconds % 60
  if (minutes < 60) return remainSeconds > 0 ? `${minutes}m ${remainSeconds}s` : `${minutes}m`
  const hours = Math.floor(minutes / 60)
  const remainMinutes = minutes % 60
  return remainMinutes > 0 ? `${hours}h ${remainMinutes}m` : `${hours}h`
}

function pipelineSupportText(pipeline?: KanbanPipelineOverview | null): string {
  if (!pipeline) return '-'
  const items = [
    pipeline.supports_history ? '历史' : '',
    pipeline.supports_retry ? '重试' : '',
    pipeline.supports_reassign ? '改派' : '',
    pipeline.supports_reclaim ? '收回' : '',
    pipeline.supports_unblock ? '解阻' : '',
    pipeline.supports_comment ? '评论' : '',
  ].filter(Boolean)
  return items.length ? items.join(' / ') : '无可用动作'
}

function guideStatusFlowText(value?: KanbanStatus[]): string {
  return (value || []).map(status => statusLabels[status] || status).join(' -> ')
}

function guideActionText(value?: string[]): string {
  return (value || []).join(' / ')
}

function notificationSummary(notification: KanbanNotification): string {
  const thread = notification.thread_id ? ` / ${notification.thread_id}` : ''
  return `${notification.platform} / ${notification.chat_id}${thread}`
}

function homeNotificationSummary(channel: KanbanHomeNotificationChannel): string {
  const name = channel.chat_name ? `${channel.chat_name} · ` : ''
  const thread = channel.thread_id ? ` / ${channel.thread_id}` : ''
  return `${name}${channel.chat_id}${thread}`
}

async function toggleHomeNotification(channel: KanbanHomeNotificationChannel) {
  if (!selectedTask.value || !channel.platform) return
  notificationBusy.value = channel.platform
  try {
    if (channel.subscribed) {
      await unsubscribeKanbanHomeNotification(selectedTask.value.id, channel.platform)
      message.success('已取消看板通知订阅')
    } else {
      await subscribeKanbanHomeNotification(selectedTask.value.id, channel.platform)
      message.success('已订阅看板通知')
    }
    await refreshSelectedTaskDrawer()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '更新通知订阅失败')
  } finally {
    notificationBusy.value = ''
  }
}

async function toggleArchivedBoards() {
  showArchivedBoards.value = !showArchivedBoards.value
  await reloadTasks()
}

function syncBoardRenames() {
  const next: Record<string, string> = {}
  boards.value.forEach(board => {
    next[board.slug] = boardRenames.value[board.slug] || board.name || board.slug
  })
  boardRenames.value = next
}

function boardLabel(board: KanbanBoard): string {
  const flags = []
  if (board.current) flags.push('当前')
  if (isArchivedBoard(board)) flags.push('已归档')
  return flags.length ? `${board.name}（${flags.join(' / ')}）` : board.name
}

function isArchivedBoard(board: KanbanBoard): boolean {
  return Boolean(board.archived)
}

async function renameBoard(board: KanbanBoard) {
  const nextName = (boardRenames.value[board.slug] || '').trim()
  if (!nextName) {
    message.error('看板名称不能为空')
    return
  }
  boardBusy.value = `rename:${board.slug}`
  try {
    await renameKanbanBoard(board.slug, nextName)
    message.success('看板已重命名')
    await reloadTasks()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '重命名看板失败')
  } finally {
    boardBusy.value = ''
  }
}

async function archiveBoard(board: KanbanBoard) {
  boardBusy.value = `archive:${board.slug}`
  try {
    const result = await removeKanbanBoard(board.slug, false)
    activeBoard.value = result.current?.slug || activeBoard.value
    message.success('看板已归档')
    await reloadTasks()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '归档看板失败')
  } finally {
    boardBusy.value = ''
  }
}

async function deleteBoard(board: KanbanBoard) {
  boardBusy.value = `delete:${board.slug}`
  try {
    const result = await removeKanbanBoard(board.slug, true)
    activeBoard.value = result.current?.slug || activeBoard.value
    message.success('看板已删除')
    await reloadTasks()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除看板失败')
  } finally {
    boardBusy.value = ''
  }
}

function executionStageLabel(stage?: string | null): string {
  const labels: Record<string, string> = {
    needs_review: '需要复核',
    running: '执行中',
    blocked: '阻塞',
    waiting_assignee: '等待分配',
    ready: '等待派发',
    planning: '规划中',
    completed: '已完成',
    archived: '已归档',
  }
  return stage ? labels[stage] || stage : '-'
}

function nextActionLabel(action?: string | null): string {
  const labels: Record<string, string> = {
    review_warnings: '处理告警',
    watch_or_reclaim: '观察或收回',
    unblock_or_retry: '解除阻塞或重试',
    assign: '分配执行人',
    dispatch: '派发执行',
    promote_when_ready: '确认后转就绪',
    review_or_edit_result: '复核或修正结果',
    restore_if_needed: '按需恢复',
    inspect: '检查任务',
  }
  return action ? labels[action] || action : '-'
}

function drawerActions() {
  return selectedDrawer.value?.actions
}

function hasWarnings(task: KanbanTask): boolean {
  return Boolean((task.warnings && task.warnings.length > 0) || task.claim_lock || task.current_run_id)
}
</script>

<template>
  <div class="kanban-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">任务看板</h2>
        <p class="header-subtitle">Jimuqu 风格多看板任务协作，使用本地 SQLite 持久化。</p>
      </div>
      <div class="header-actions">
        <NSelect
          v-model:value="activeBoard"
          :options="boardOptions"
          size="small"
          class="board-select"
          @update:value="handleBoardChange"
        />
        <NSelect
          v-model:value="tenantFilter"
          :options="tenantOptions"
          size="small"
          clearable
          filterable
          tag
          class="filter-select"
          placeholder="租户"
          @update:value="handleFilterChange"
        />
        <NSelect
          v-model:value="assigneeFilter"
          :options="assigneeOptions"
          size="small"
          clearable
          filterable
          tag
          class="filter-select"
          placeholder="执行人"
          @update:value="handleFilterChange"
        />
        <NInput
          v-model:value="search"
          size="small"
          clearable
          class="search-input"
          placeholder="搜索任务"
        />
        <NButton size="small" @click="showBoardModal = true">新建看板</NButton>
        <NButton size="small" @click="showBoardManager = true">管理看板</NButton>
        <NButton size="small" :loading="dispatching" @click="runDispatcher(true)">预检派发</NButton>
        <NButton size="small" :loading="dispatching" @click="runDispatcher(false)">派发执行</NButton>
        <span class="daemon-status" :class="{ active: daemon?.running }">
          {{ daemon?.running ? `后台派发中 · ${daemon.tick_count || 0} 次` : '后台派发未启动' }}
        </span>
        <NInputNumber v-model:value="daemonInterval" size="small" class="daemon-number" :min="1" :show-button="false" placeholder="间隔秒" />
        <NInputNumber v-model:value="daemonMaxSpawn" size="small" class="daemon-number" :min="1" :show-button="false" placeholder="并发数" />
        <NButton v-if="!daemon?.running" size="small" :loading="daemonBusy" @click="startDaemon">启动后台派发</NButton>
        <NButton v-else size="small" type="warning" :loading="daemonBusy" @click="stopDaemon">停止后台派发</NButton>
        <NButton type="primary" size="small" @click="openCreateTask">新建任务</NButton>
      </div>
    </header>

    <section v-if="guide" class="guide-panel" :class="{ expanded: showGuide }">
      <div class="guide-summary">
        <div>
          <div class="guide-title">看板流程指南</div>
          <div class="guide-objective">{{ guide.objective }}</div>
        </div>
        <div class="guide-meta">
          <span>{{ guide.board?.slug || activeBoard }}</span>
          <span>任务 {{ guide.stats?.total || 0 }}</span>
          <span>{{ guideStatusFlowText(guide.status_flow) }}</span>
        </div>
        <NButton size="small" quaternary @click="showGuide = !showGuide">
          {{ showGuide ? '收起指南' : '展开指南' }}
        </NButton>
      </div>
      <div v-if="showGuide" class="guide-body">
        <div class="guide-steps">
          <div v-for="step in guide.steps" :key="step.order" class="guide-step">
            <span class="guide-step-order">{{ step.order }}</span>
            <div>
              <strong>{{ step.title }}</strong>
              <p>{{ step.description }}</p>
              <code>{{ step.commands.join('  |  ') }}</code>
            </div>
          </div>
        </div>
        <div class="guide-actions">
          <div>
            <span>抽屉区块</span>
            <strong>{{ guideActionText(guide.drawer_sections) }}</strong>
          </div>
          <div>
            <span>恢复动作</span>
            <strong>{{ guideActionText(guide.recovery_actions) }}</strong>
          </div>
          <div>
            <span>自动化动作</span>
            <strong>{{ guideActionText(guide.automation_actions) }}</strong>
          </div>
        </div>
      </div>
    </section>

    <NSpin :show="loading">
      <div class="kanban-board">
        <section v-for="column in taskColumns" :key="column.status" class="kanban-column">
          <div class="column-header">
            <span>{{ column.title }}</span>
            <span class="count">{{ column.tasks.length }}</span>
          </div>
          <div class="task-list">
            <article v-for="task in column.tasks" :key="task.id" class="task-card" @click="openTask(task)">
              <div class="task-topline">
                <span class="task-id">{{ task.id }}</span>
                <span class="task-flags">
                  <span v-if="task.status === 'running' && task.claim_lock" class="claim-flag">已锁定</span>
                  <span v-if="hasWarnings(task)" class="warning-flag">需处理</span>
                  <span class="priority">P{{ task.priority || 0 }}</span>
                </span>
              </div>
              <h3>{{ task.title }}</h3>
              <p v-if="task.body">{{ task.body }}</p>
              <div class="task-meta">
                <span>@{{ task.assignee || '未分配' }}</span>
                <span v-if="task.tenant">{{ task.tenant }}</span>
              </div>
              <div v-if="taskPipelineLabel(task) || (task.parents || []).length || (task.children || []).length" class="task-pipeline">
                <span v-if="taskPipelineLabel(task)">{{ taskPipelineLabel(task) }}</span>
                <span v-if="(task.parents || []).length">前置 {{ (task.parents || []).length }}</span>
                <span v-if="(task.children || []).length">后续 {{ (task.children || []).length }}</span>
              </div>
              <div class="task-actions" @click.stop>
                <NButton
                  v-for="status in visibleStatuses"
                  :key="status"
                  size="tiny"
                  quaternary
                  :type="task.status === status ? 'primary' : 'default'"
                  @click="moveTask(task, status)"
                >
                  {{ statusLabels[status] }}
                </NButton>
              </div>
            </article>
            <div v-if="column.tasks.length === 0" class="empty-column">暂无任务</div>
          </div>
        </section>
      </div>
    </NSpin>

    <NModal v-model:show="showTaskModal" preset="card" class="task-modal" :title="selectedTask ? '编辑任务' : '新建任务'">
      <div class="modal-form">
        <label>
          <span>标题</span>
          <NInput v-model:value="taskForm.title" placeholder="任务标题" />
        </label>
        <label>
          <span>描述</span>
          <NInput v-model:value="taskForm.body" type="textarea" placeholder="任务背景、验收标准或上下文" />
        </label>
        <div class="form-grid">
          <label>
            <span>前置任务</span>
            <NInput v-model:value="taskForm.parents" placeholder="task-1, task-2" />
          </label>
          <label>
            <span>技能绑定</span>
            <NInput v-model:value="taskForm.skills" placeholder="skill-a, skill-b" />
          </label>
          <label>
            <span>流程模板</span>
            <NInput v-model:value="taskForm.workflow_template_id" placeholder="可选" />
          </label>
          <label>
            <span>当前步骤</span>
            <NInput v-model:value="taskForm.current_step_key" placeholder="例如 draft / review / deliver" />
          </label>
        </div>
        <div class="form-grid">
          <label>
            <span>状态</span>
            <NSelect v-model:value="taskForm.status" :options="statusOptions" />
          </label>
          <label>
            <span>优先级</span>
            <NInputNumber v-model:value="taskForm.priority" :min="0" :max="9" />
          </label>
          <label>
            <span>最大重试</span>
            <NInputNumber v-model:value="taskForm.max_retries" :min="1" clearable placeholder="跟随派发器" />
          </label>
          <label>
            <span>执行人</span>
            <NInput v-model:value="taskForm.assignee" placeholder="agent/profile/user" />
          </label>
          <label>
            <span>租户/命名空间</span>
            <NInput v-model:value="taskForm.tenant" placeholder="可选" />
          </label>
        </div>
        <div v-if="selectedTask" class="comments">
          <div class="detail-strip">
            <span>启动失败 {{ selectedTask.spawn_failures || 0 }} 次</span>
            <span>最大重试 {{ selectedTask.max_retries || '跟随派发器' }}</span>
            <span v-if="selectedTask.workflow_template_id">流程 {{ selectedTask.workflow_template_id }}</span>
            <span v-if="selectedTask.current_step_key">步骤 {{ selectedTask.current_step_key }}</span>
            <span v-if="skillsText(selectedTask.skills)">技能 {{ skillsText(selectedTask.skills) }}</span>
          </div>
          <div class="pipeline-panel">
            <div class="panel-title">流程步骤推进</div>
            <div class="pipeline-actions">
              <NInput v-model:value="stepForm.workflow_template_id" placeholder="流程模板，例如 delivery" />
              <NInput v-model:value="stepForm.step_key" placeholder="目标步骤，例如 review" />
              <NInput v-model:value="stepForm.note" placeholder="推进说明" />
              <NButton type="primary" @click="advanceSelectedStep">记录推进</NButton>
            </div>
          </div>
          <div v-if="(selectedTask.parents || []).length || (selectedTask.children || []).length" class="task-relations">
            <div v-if="(selectedTask.parents || []).length">
              <div class="panel-title">前置任务</div>
              <div v-for="parent in selectedTask.parents || []" :key="parent.id" class="relation-row">
                {{ taskRefLabel(parent) }}
              </div>
            </div>
            <div v-if="(selectedTask.children || []).length">
              <div class="panel-title">后续任务</div>
              <div v-for="child in selectedTask.children || []" :key="child.id" class="relation-row">
                {{ taskRefLabel(child) }}
              </div>
            </div>
          </div>
          <div v-if="selectedDrawer?.pipeline_overview" class="pipeline-overview">
            <div class="panel-title">执行流水概览</div>
            <div class="overview-grid">
              <div>
                <span>流程模板</span>
                <strong>{{ selectedDrawer.pipeline_overview.workflow_template_id || '-' }}</strong>
              </div>
              <div>
                <span>当前步骤</span>
                <strong>{{ selectedDrawer.pipeline_overview.current_step_key || '-' }}</strong>
              </div>
              <div>
                <span>结构化任务</span>
                <strong>{{ selectedDrawer.pipeline_overview.schema_task ? '是' : '否' }}</strong>
              </div>
              <div>
                <span>执行人</span>
                <strong>{{ selectedDrawer.pipeline_overview.assignee || '未分配' }}</strong>
              </div>
              <div>
                <span>Worker</span>
                <strong>{{ selectedDrawer.pipeline_overview.worker_id || '-' }}</strong>
              </div>
              <div>
                <span>运行锁</span>
                <strong>{{ selectedDrawer.pipeline_overview.claim_lock || '-' }}</strong>
              </div>
              <div>
                <span>重试次数</span>
                <strong>{{ selectedDrawer.pipeline_overview.retry_count || 0 }}</strong>
              </div>
              <div>
                <span>事件数量</span>
                <strong>{{ selectedDrawer.pipeline_overview.event_count }}</strong>
              </div>
              <div>
                <span>可用动作</span>
                <strong>{{ pipelineSupportText(selectedDrawer.pipeline_overview) }}</strong>
              </div>
            </div>
            <div class="pipeline-run-grid">
              <div>
                <span>活跃运行</span>
                <strong>{{ runSummaryStatusLabel(selectedDrawer.pipeline_overview.active_run) }}</strong>
                <p>{{ runSummaryText(selectedDrawer.pipeline_overview.active_run) }}</p>
              </div>
              <div>
                <span>最近运行</span>
                <strong>{{ runSummaryStatusLabel(selectedDrawer.pipeline_overview.latest_run) }}</strong>
                <p>{{ runSummaryText(selectedDrawer.pipeline_overview.latest_run) }}</p>
              </div>
            </div>
          </div>
          <div v-if="selectedDrawer?.execution_overview" class="execution-overview">
            <div class="panel-title">执行概览</div>
            <div class="overview-grid">
              <div>
                <span>阶段</span>
                <strong>{{ executionStageLabel(selectedDrawer.execution_overview.stage) }}</strong>
              </div>
              <div>
                <span>建议动作</span>
                <strong>{{ nextActionLabel(selectedDrawer.execution_overview.next_action) }}</strong>
              </div>
              <div>
                <span>尝试次数</span>
                <strong>{{ selectedDrawer.execution_overview.attempt_count }}</strong>
              </div>
              <div>
                <span>告警</span>
                <strong>{{ selectedDrawer.execution_overview.warning_count }}</strong>
              </div>
              <div>
                <span>最后执行人</span>
                <strong>{{ selectedDrawer.execution_overview.last_worker || '-' }}</strong>
              </div>
              <div>
                <span>最后耗时</span>
                <strong>{{ formatDuration(selectedDrawer.execution_overview.last_duration_ms) || '-' }}</strong>
              </div>
              <div>
                <span>超时</span>
                <strong>{{ selectedDrawer.execution_overview.last_timed_out ? '是' : '否' }}</strong>
              </div>
              <div>
                <span>最后事件</span>
                <strong>{{ selectedDrawer.execution_overview.last_event_kind || '-' }}</strong>
              </div>
            </div>
            <div v-if="selectedDrawer.execution_overview.last_event_summary" class="overview-summary">
              {{ selectedDrawer.execution_overview.last_event_summary }}
            </div>
          </div>
          <div
            v-if="(selectedTask.warnings || []).length || selectedTask.claim_lock || (selectedTask.runs || []).length"
            class="recovery-panel"
          >
            <div class="panel-title">恢复与异常</div>
            <div v-if="selectedTask.claim_lock" class="claim-detail">
              <span>运行锁：{{ selectedTask.claim_lock }}</span>
              <span v-if="selectedTask.worker_id">worker={{ selectedTask.worker_id }}</span>
            </div>
            <div v-for="warning in selectedTask.warnings || []" :key="warning.id" class="warning-row">
              {{ eventSummary(warning) }}
            </div>
            <div class="recovery-actions">
              <NInput v-model:value="recoveryReason" placeholder="恢复原因，例如 worker timeout" />
              <NButton :disabled="drawerActions()?.can_reclaim === false" @click="reclaimSelectedTask">收回执行权</NButton>
              <NButton :disabled="drawerActions()?.can_retry === false" @click="retrySelectedTask">重试任务</NButton>
            </div>
            <div class="recovery-actions">
              <NInput v-model:value="reassignAssignee" placeholder="新的执行人" />
              <NButton :disabled="drawerActions()?.can_reassign === false" @click="reassignSelectedTask(false)">重新分配</NButton>
              <NButton type="primary" :disabled="drawerActions()?.can_reclaim === false" @click="reassignSelectedTask(true)">收回并分配</NButton>
            </div>
          </div>

          <div v-if="selectedTask.worker_context || selectedDrawer?.context?.worker_context" class="worker-context">
            <div class="comments-title">执行上下文</div>
            <pre>{{ selectedDrawer?.context?.worker_context || selectedTask.worker_context }}</pre>
          </div>

          <div v-if="(selectedTask.runs || []).length" class="runs">
            <div class="comments-title">运行历史</div>
            <div v-for="run in selectedTask.runs || []" :key="run.id" class="run-row">
              <div class="run-head">
                <span class="run-status" :class="runTone(run)">{{ runStatusLabel(run) }}</span>
                <strong>{{ runSummary(run) }}</strong>
                <span class="run-time">{{ run.run_id || run.id }}</span>
              </div>
              <div class="run-grid">
                <div>
                  <span>Profile</span>
                  <strong>{{ run.profile || '-' }}</strong>
                </div>
                <div>
                  <span>步骤</span>
                  <strong>{{ run.step_key || '-' }}</strong>
                </div>
                <div>
                  <span>Worker</span>
                  <strong>{{ run.worker_id || '-' }}</strong>
                </div>
                <div>
                  <span>PID</span>
                  <strong>{{ run.worker_pid || '-' }}</strong>
                </div>
                <div>
                  <span>状态</span>
                  <strong>{{ runStateLabel(run) }}</strong>
                </div>
                <div>
                  <span>最大运行</span>
                  <strong>{{ run.max_runtime_seconds ? `${run.max_runtime_seconds}s` : '-' }}</strong>
                </div>
              </div>
              <div v-if="runTimingSummary(run)" class="run-line">{{ runTimingSummary(run) }}</div>
              <div v-if="run.error" class="run-error">{{ run.error }}</div>
              <div v-if="runMetadataSummary(run)" class="run-metadata">{{ runMetadataSummary(run) }}</div>
            </div>
          </div>

          <div v-if="(selectedTask.events || []).length" class="events">
            <div class="comments-title">执行流水</div>
            <div v-for="event in selectedTask.events || []" :key="event.id" class="event-row">
              <span class="event-kind">{{ event.kind }}</span>
              <span>{{ eventSummary(event) }}</span>
            </div>
          </div>

          <div v-if="homeNotificationChannels.length || (selectedDrawer?.notifications || []).length" class="notifications">
            <div class="comments-title">通知投递</div>
            <div v-if="homeNotificationChannels.length" class="home-notification-list">
              <div v-for="channel in homeNotificationChannels" :key="channel.platform" class="home-notification-row">
                <div>
                  <strong>{{ channel.platform }}</strong>
                  <span>{{ homeNotificationSummary(channel) }}</span>
                </div>
                <NButton
                  size="tiny"
                  :type="channel.subscribed ? 'default' : 'primary'"
                  :loading="notificationBusy === channel.platform"
                  @click="toggleHomeNotification(channel)"
                >
                  {{ channel.subscribed ? '取消订阅' : '订阅' }}
                </NButton>
              </div>
            </div>
            <div v-else class="empty-note">未配置可用的 home channel</div>
            <div v-for="notification in selectedDrawer?.notifications || []" :key="notification.id" class="notification-row">
              <span>{{ notificationSummary(notification) }}</span>
              <span>{{ notification.created_at }}</span>
            </div>
          </div>

          <div v-if="selectedDrawer?.log" class="task-log">
            <div class="comments-title">执行日志</div>
            <div class="log-meta">
              <span>{{ selectedDrawer.log.exists ? `已记录 ${selectedDrawer.log.size || 0} bytes` : '暂无日志文件' }}</span>
              <span>{{ selectedDrawer.log.path }}</span>
            </div>
            <pre v-if="selectedDrawer.log.content">{{ selectedDrawer.log.content }}</pre>
          </div>

          <div class="comments-title">评论</div>
          <div v-for="comment in selectedTask.comments || []" :key="comment.id" class="comment">
            <strong>{{ comment.author }}</strong>
            <span>{{ comment.body }}</span>
          </div>
          <div class="comment-box">
            <NInput v-model:value="commentText" placeholder="追加评论" @keyup.enter="saveComment" />
            <NButton @click="saveComment">发送</NButton>
          </div>
        </div>
      </div>
      <template #footer>
        <div class="modal-actions">
          <NButton @click="showTaskModal = false">取消</NButton>
          <NButton type="primary" @click="saveTask">保存</NButton>
        </div>
      </template>
    </NModal>

    <NModal v-model:show="showBoardModal" preset="card" class="board-modal" title="新建看板">
      <div class="modal-form">
        <label>
          <span>标识</span>
          <NInput v-model:value="boardForm.slug" placeholder="demo-board" />
        </label>
        <label>
          <span>名称</span>
          <NInput v-model:value="boardForm.name" placeholder="看板名称" />
        </label>
        <label>
          <span>说明</span>
          <NInput v-model:value="boardForm.description" type="textarea" placeholder="可选" />
        </label>
      </div>
      <template #footer>
        <div class="modal-actions">
          <NButton @click="showBoardModal = false">取消</NButton>
          <NButton type="primary" @click="saveBoard">创建并切换</NButton>
        </div>
      </template>
    </NModal>

    <NModal v-model:show="showBoardManager" preset="card" class="board-manager-modal" title="管理看板">
      <div class="board-manager-toolbar">
        <NButton size="small" @click="toggleArchivedBoards">
          {{ showArchivedBoards ? '隐藏已归档' : '显示已归档' }}
        </NButton>
        <span>当前共 {{ boards.length }} 个看板</span>
      </div>
      <div class="board-manager-list">
        <div v-for="board in boards" :key="board.slug" class="board-manager-row">
          <div class="board-main">
            <div class="board-title">
              <span>{{ boardLabel(board) }}</span>
              <small>{{ board.slug }}</small>
            </div>
            <div class="board-counts">
              <span v-for="status in visibleStatuses" :key="status">
                {{ statusLabels[status] }} {{ board.counts?.[status] || 0 }}
              </span>
            </div>
          </div>
          <NInput
            v-model:value="boardRenames[board.slug]"
            size="small"
            class="board-name-input"
            :disabled="isArchivedBoard(board)"
          />
          <div class="board-row-actions">
            <NButton size="small" :disabled="isArchivedBoard(board)" :loading="boardBusy === `rename:${board.slug}`" @click="renameBoard(board)">
              重命名
            </NButton>
            <NButton
              size="small"
              :disabled="board.current || isArchivedBoard(board)"
              :loading="boardBusy === `archive:${board.slug}`"
              @click="archiveBoard(board)"
            >
              归档
            </NButton>
            <NButton
              size="small"
              type="error"
              :disabled="board.current || board.slug === 'default'"
              :loading="boardBusy === `delete:${board.slug}`"
              @click="deleteBoard(board)"
            >
              删除
            </NButton>
          </div>
        </div>
      </div>
      <template #footer>
        <div class="modal-actions">
          <NButton @click="showBoardManager = false">关闭</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.kanban-view {
  height: calc(100 * var(--vh));
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.header-subtitle {
  margin: 4px 0 0;
  color: $text-muted;
  font-size: 13px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.daemon-status {
  height: 28px;
  display: inline-flex;
  align-items: center;
  padding: 0 10px;
  border: 1px solid $border-color;
  border-radius: 6px;
  color: $text-muted;
  font-size: 12px;
  white-space: nowrap;

  &.active {
    border-color: #16a34a;
    color: #15803d;
  }
}

.daemon-number {
  width: 82px;
}

.board-select {
  width: 220px;
}

.filter-select {
  width: 150px;
}

.search-input {
  width: 180px;
}

.guide-panel {
  border-top: 1px solid $border-color;
  border-bottom: 1px solid $border-color;
  padding: 10px 16px;
  background: $bg-card;
}

.guide-summary {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(240px, auto) auto;
  gap: 12px;
  align-items: center;
}

.guide-title {
  color: $text-primary;
  font-size: 13px;
  font-weight: 600;
}

.guide-objective {
  margin-top: 3px;
  color: $text-muted;
  font-size: 12px;
  line-height: 1.4;
}

.guide-meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;

  span {
    border: 1px solid $border-color;
    border-radius: 4px;
    padding: 3px 6px;
    color: $text-muted;
    font-size: 11px;
    line-height: 1.2;
  }
}

.guide-body {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(260px, 0.6fr);
  gap: 12px;
  margin-top: 10px;
}

.guide-steps {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.guide-step {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  gap: 8px;
  border: 1px solid $border-color;
  border-radius: 6px;
  padding: 8px;
  background: $bg-card-hover;

  strong,
  p,
  code {
    display: block;
    overflow-wrap: anywhere;
  }

  strong {
    color: $text-primary;
    font-size: 13px;
  }

  p {
    margin: 4px 0 6px;
    color: $text-secondary;
    font-size: 12px;
    line-height: 1.4;
  }

  code {
    color: $text-muted;
    font-family: $font-code;
    font-size: 11px;
    line-height: 1.35;
  }
}

.guide-step-order {
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid $border-color;
  border-radius: 50%;
  color: $text-secondary;
  font-size: 11px;
  font-weight: 600;
}

.guide-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;

  div {
    border: 1px solid $border-color;
    border-radius: 6px;
    padding: 8px;
    background: $bg-card-hover;
  }

  span,
  strong {
    display: block;
    overflow-wrap: anywhere;
  }

  span {
    color: $text-muted;
    font-size: 11px;
    margin-bottom: 4px;
  }

  strong {
    color: $text-secondary;
    font-size: 12px;
    font-weight: 500;
    line-height: 1.4;
  }
}

.kanban-board {
  height: calc(100 * var(--vh) - 141px);
  display: grid;
  grid-template-columns: repeat(6, minmax(210px, 1fr));
  gap: 12px;
  overflow-x: auto;
  padding: 16px;
}

.kanban-column {
  min-width: 210px;
  border-left: 1px solid $border-color;
  padding-left: 12px;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.column-header {
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: $text-primary;
  font-weight: 600;
  font-size: 13px;

  .count {
    color: $text-muted;
    font-weight: 500;
  }
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
  padding-right: 4px;
  min-height: 0;
}

.task-card {
  border: 1px solid $border-color;
  background: $bg-card;
  border-radius: 6px;
  padding: 10px;
  cursor: pointer;
  transition: border-color $transition-fast, background $transition-fast;

  &:hover {
    border-color: $text-muted;
    background: $bg-card-hover;
  }

  h3 {
    margin: 6px 0;
    color: $text-primary;
    font-size: 14px;
    line-height: 1.35;
    font-weight: 600;
  }

  p {
    margin: 0 0 8px;
    color: $text-secondary;
    font-size: 12px;
    line-height: 1.45;
    display: -webkit-box;
    -webkit-line-clamp: 3;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
}

.task-topline,
.task-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: $text-muted;
  font-size: 11px;
}

.task-pipeline {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 7px;

  span {
    min-width: 0;
    border: 1px solid $border-color;
    border-radius: 4px;
    padding: 2px 5px;
    color: $text-muted;
    font-size: 10px;
    line-height: 1.2;
    overflow-wrap: anywhere;
  }
}

.task-id {
  font-family: $font-code;
}

.priority {
  color: $text-secondary;
}

.task-flags {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
}

.claim-flag,
.warning-flag {
  height: 18px;
  display: inline-flex;
  align-items: center;
  border-radius: 4px;
  padding: 0 5px;
  font-size: 10px;
  line-height: 1;
}

.claim-flag {
  color: #1d4ed8;
  background: rgba(37, 99, 235, 0.12);
}

.warning-flag {
  color: #b45309;
  background: rgba(245, 158, 11, 0.16);
}

.task-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  margin-top: 8px;
}

.empty-column {
  height: 72px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed $border-color;
  border-radius: 6px;
  color: $text-muted;
  font-size: 12px;
}

.task-modal,
.board-modal,
.board-manager-modal {
  width: min(720px, calc(100vw - 32px));
}

.board-manager-modal {
  width: min(960px, calc(100vw - 32px));
}

.modal-form {
  display: flex;
  flex-direction: column;
  gap: 14px;

  label {
    display: flex;
    flex-direction: column;
    gap: 6px;
    color: $text-secondary;
    font-size: 12px;
  }
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.comments {
  border-top: 1px solid $border-color;
  padding-top: 12px;
}

.detail-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: $text-muted;
  font-size: 12px;
  margin-bottom: 12px;
}

.recovery-panel,
.execution-overview,
.pipeline-overview,
.pipeline-panel,
.task-relations,
.runs,
.events,
.notifications,
.task-log,
.worker-context {
  border-bottom: 1px solid $border-color;
  padding-bottom: 12px;
  margin-bottom: 12px;
}

.panel-title,
.comments-title {
  color: $text-primary;
  font-weight: 600;
  margin-bottom: 8px;
}

.claim-detail,
.warning-row,
.overview-summary,
.run-row,
.event-row,
.notification-row,
.log-meta {
  color: $text-secondary;
  font-size: 12px;
  line-height: 1.45;
}

.claim-detail {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.warning-row {
  color: #b45309;
  margin-bottom: 6px;
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;

  div {
    min-width: 0;
    border: 1px solid $border-color;
    border-radius: 6px;
    padding: 8px;
    background: $bg-card-hover;
  }

  span,
  strong {
    display: block;
    overflow-wrap: anywhere;
  }

  span {
    color: $text-muted;
    font-size: 11px;
    margin-bottom: 4px;
  }

  strong {
    color: $text-primary;
    font-size: 13px;
    font-weight: 600;
  }
}

.pipeline-run-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 8px;

  div {
    min-width: 0;
    border: 1px solid $border-color;
    border-radius: 6px;
    padding: 8px;
    background: $bg-card-hover;
  }

  span,
  strong,
  p {
    display: block;
    overflow-wrap: anywhere;
  }

  span {
    color: $text-muted;
    font-size: 11px;
    margin-bottom: 4px;
  }

  strong {
    color: $text-primary;
    font-size: 13px;
    font-weight: 600;
  }

  p {
    margin: 5px 0 0;
    color: $text-secondary;
    font-size: 12px;
    line-height: 1.4;
  }
}

.overview-summary {
  margin-top: 8px;
}

.task-relations {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.relation-row {
  border: 1px solid $border-color;
  border-radius: 6px;
  padding: 7px 8px;
  color: $text-secondary;
  font-size: 12px;
  line-height: 1.4;
  overflow-wrap: anywhere;

  & + & {
    margin-top: 6px;
  }
}

.recovery-actions {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 8px;
  margin-top: 8px;
  align-items: center;
}

.pipeline-actions {
  display: grid;
  grid-template-columns: minmax(120px, 0.9fr) minmax(120px, 0.9fr) minmax(160px, 1.2fr) auto;
  gap: 8px;
  align-items: center;
}

.event-row {
  display: grid;
  grid-template-columns: 170px minmax(0, 1fr);
  gap: 8px;
  padding: 5px 0;
}

.notification-row,
.log-meta {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 180px;
  gap: 8px;
  padding: 5px 0;
}

.home-notification-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 8px;
}

.home-notification-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  border: 1px solid $border-color;
  border-radius: 6px;
  padding: 7px 8px;
  background: $bg-card-hover;

  div,
  strong,
  span {
    min-width: 0;
  }

  strong,
  span {
    display: block;
    overflow-wrap: anywhere;
  }

  strong {
    color: $text-primary;
    font-size: 12px;
    text-transform: uppercase;
  }

  span {
    color: $text-secondary;
    font-size: 12px;
  }
}

.empty-note {
  color: $text-muted;
  font-size: 12px;
  margin-bottom: 8px;
}

.worker-context pre,
.task-log pre {
  max-height: 220px;
  overflow: auto;
  margin: 0;
  padding: 10px;
  border: 1px solid $border-color;
  border-radius: 6px;
  background: $bg-card-hover;
  color: $text-secondary;
  font-family: $font-code;
  font-size: 12px;
  line-height: 1.45;
  white-space: pre-wrap;
  word-break: break-word;
}

.event-kind,
.run-status,
.run-time {
  color: $text-muted;
  font-family: $font-code;
  font-size: 11px;
}

.run-row {
  border: 1px solid $border-color;
  border-radius: 6px;
  padding: 10px;
  margin-bottom: 8px;
  background: $bg-card-hover;
}

.run-head {
  display: grid;
  grid-template-columns: 82px minmax(0, 1fr) minmax(120px, auto);
  gap: 8px;
  align-items: center;

  strong {
    color: $text-primary;
    font-size: 13px;
    font-weight: 600;
    overflow-wrap: anywhere;
  }
}

.run-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
  margin-top: 8px;

  div {
    min-width: 0;
  }

  span,
  strong {
    display: block;
    overflow-wrap: anywhere;
  }

  span {
    color: $text-muted;
    font-size: 11px;
  }

  strong {
    color: $text-secondary;
    font-size: 12px;
    font-weight: 500;
  }
}

.run-line,
.run-metadata,
.run-error {
  margin-top: 8px;
  overflow-wrap: anywhere;
}

.run-metadata {
  color: $text-muted;
  font-family: $font-code;
  font-size: 11px;
}

.run-error {
  color: #b91c1c;
  font-family: $font-code;
  font-size: 11px;
}

.run-status {
  border: 1px solid $border-color;
  border-radius: 999px;
  padding: 2px 7px;
  text-align: center;
}

.run-status.active {
  color: #1d4ed8;
  border-color: #93c5fd;
  background: #eff6ff;
}

.run-status.timeout {
  color: #b91c1c;
  border-color: #fecaca;
  background: #fef2f2;
}

.run-status.failed {
  color: #b91c1c;
  border-color: #fecaca;
  background: #fef2f2;
}

.run-status.success {
  color: #047857;
  border-color: #a7f3d0;
  background: #ecfdf5;
}

.comment {
  display: flex;
  gap: 8px;
  color: $text-secondary;
  font-size: 13px;
  padding: 6px 0;
}

.comment-box {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.board-manager-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: $text-muted;
  font-size: 12px;
  margin-bottom: 12px;
}

.board-manager-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: min(580px, calc(100vh - 220px));
  overflow: auto;
}

.board-manager-row {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(180px, 260px) auto;
  gap: 10px;
  align-items: center;
  border: 1px solid $border-color;
  border-radius: 6px;
  padding: 10px;
  background: $bg-card;
}

.board-main {
  min-width: 0;
}

.board-title {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
  color: $text-primary;
  font-size: 13px;
  font-weight: 600;

  small {
    color: $text-muted;
    font-family: $font-code;
    font-size: 11px;
    font-weight: 400;
  }
}

.board-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
  color: $text-muted;
  font-size: 11px;
}

.board-name-input {
  min-width: 0;
}

.board-row-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

@media (max-width: $breakpoint-mobile) {
  .page-header {
    align-items: stretch;
  }

  .header-actions {
    flex-wrap: wrap;
  }

  .board-select {
    width: 100%;
  }

  .filter-select,
  .search-input {
    width: 100%;
  }

  .guide-summary,
  .guide-body,
  .guide-steps {
    grid-template-columns: 1fr;
  }

  .guide-meta {
    justify-content: flex-start;
  }

  .daemon-status,
  .daemon-number {
    width: 100%;
  }

  .kanban-board {
    grid-template-columns: repeat(6, 240px);
    height: calc(100 * var(--vh) - 214px);
  }

  .form-grid {
    grid-template-columns: 1fr;
  }

  .pipeline-actions,
  .recovery-actions,
  .overview-grid,
  .pipeline-run-grid,
  .task-relations,
  .run-head,
  .run-grid,
  .run-row,
  .event-row,
  .notification-row,
  .log-meta {
    grid-template-columns: 1fr;
  }

  .board-manager-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .board-manager-row {
    grid-template-columns: 1fr;
  }

  .board-row-actions {
    justify-content: flex-start;
  }
}
</style>
