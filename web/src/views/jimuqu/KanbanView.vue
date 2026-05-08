<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NInput, NInputNumber, NModal, NSelect, NSpin, useMessage } from 'naive-ui'
import {
  addKanbanComment,
  createKanbanBoard,
  createKanbanTask,
  dispatchKanban,
  fetchKanbanBoards,
  fetchKanbanDaemon,
  fetchKanbanTaskDrawer,
  fetchKanbanTasks,
  kanbanStatuses,
  moveKanbanTask,
  reclaimKanbanTask,
  reassignKanbanTask,
  retryKanbanTask,
  startKanbanDaemon,
  stopKanbanDaemon,
  switchKanbanBoard,
  updateKanbanTask,
  type KanbanBoard,
  type KanbanDaemonStatus,
  type KanbanEvent,
  type KanbanNotification,
  type KanbanRun,
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
const selectedTask = ref<KanbanTask | null>(null)
const selectedDrawer = ref<KanbanTaskDrawer | null>(null)
const commentText = ref('')
const recoveryReason = ref('')
const reassignAssignee = ref('')
const dispatching = ref(false)
const daemonBusy = ref(false)
const daemon = ref<KanbanDaemonStatus | null>(null)
const daemonInterval = ref(60)
const daemonMaxSpawn = ref(3)
const tenantFilter = ref<string | null>('')
const assigneeFilter = ref<string | null>('')
const search = ref('')
const taskForm = ref({
  title: '',
  body: '',
  assignee: '',
  status: 'todo' as KanbanStatus,
  priority: 0,
  max_retries: null as number | null,
  tenant: '',
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
  label: board.current ? `${board.name}（当前）` : board.name,
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
    boards.value = await fetchKanbanBoards()
    activeBoard.value = boards.value.find(board => board.current)?.slug || boards.value[0]?.slug || ''
    tasks.value = await fetchKanbanTasks(taskQuery())
    daemon.value = await fetchKanbanDaemon()
  } finally {
    loading.value = false
  }
}

async function reloadTasks() {
  tasks.value = await fetchKanbanTasks(taskQuery())
  boards.value = await fetchKanbanBoards()
  daemon.value = await fetchKanbanDaemon()
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
  }
  showTaskModal.value = true
}

async function openTask(task: KanbanTask) {
  selectedDrawer.value = await fetchKanbanTaskDrawer(task.id)
  selectedTask.value = selectedDrawer.value.task
  taskForm.value = {
    title: selectedTask.value.title,
    body: selectedTask.value.body || '',
    assignee: selectedTask.value.assignee || '',
    status: selectedTask.value.status,
    priority: selectedTask.value.priority || 0,
    max_retries: selectedTask.value.max_retries || null,
    tenant: selectedTask.value.tenant || '',
  }
  commentText.value = ''
  recoveryReason.value = ''
  reassignAssignee.value = selectedTask.value.assignee || ''
  showTaskModal.value = true
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
  }
  if (selectedTask.value) {
    await updateKanbanTask(selectedTask.value.id, payload)
    message.success('任务已更新')
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
  selectedDrawer.value = await fetchKanbanTaskDrawer(selectedTask.value.id)
  selectedTask.value = selectedDrawer.value.task
  commentText.value = ''
  await reloadTasks()
}

async function reclaimSelectedTask() {
  if (!selectedTask.value) return
  try {
    selectedTask.value = await reclaimKanbanTask(selectedTask.value.id, recoveryReason.value.trim() || 'dashboard')
    selectedDrawer.value = await fetchKanbanTaskDrawer(selectedTask.value.id)
    selectedTask.value = selectedDrawer.value.task
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
    selectedDrawer.value = await fetchKanbanTaskDrawer(selectedTask.value.id)
    selectedTask.value = selectedDrawer.value.task
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
    selectedDrawer.value = await fetchKanbanTaskDrawer(selectedTask.value.id)
    selectedTask.value = selectedDrawer.value.task
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
  return event.kind
}

function runSummary(run: KanbanRun): string {
  const outcome = run.outcome || run.status
  const worker = run.worker_id || run.profile || '-'
  const summary = run.summary ? `：${run.summary}` : ''
  return `${outcome} / ${worker}${summary}`
}

function notificationSummary(notification: KanbanNotification): string {
  const thread = notification.thread_id ? ` / ${notification.thread_id}` : ''
  return `${notification.platform} / ${notification.chat_id}${thread}`
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
              <span class="run-status" :class="{ active: !run.ended_at }">{{ run.status }}</span>
              <span>{{ runSummary(run) }}</span>
              <span class="run-time">{{ run.started_at || '-' }}</span>
            </div>
          </div>

          <div v-if="(selectedTask.events || []).length" class="events">
            <div class="comments-title">执行流水</div>
            <div v-for="event in selectedTask.events || []" :key="event.id" class="event-row">
              <span class="event-kind">{{ event.kind }}</span>
              <span>{{ eventSummary(event) }}</span>
            </div>
          </div>

          <div v-if="(selectedDrawer?.notifications || []).length" class="notifications">
            <div class="comments-title">通知投递</div>
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

.kanban-board {
  height: calc(100 * var(--vh) - 84px);
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
.board-modal {
  width: min(720px, calc(100vw - 32px));
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

.overview-summary {
  margin-top: 8px;
}

.recovery-actions {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 8px;
  margin-top: 8px;
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

.run-row {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr) 150px;
  gap: 8px;
  padding: 5px 0;
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

.run-status.active {
  color: #1d4ed8;
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

  .daemon-status,
  .daemon-number {
    width: 100%;
  }

  .kanban-board {
    grid-template-columns: repeat(6, 240px);
    height: calc(100 * var(--vh) - 132px);
  }

  .form-grid {
    grid-template-columns: 1fr;
  }

  .recovery-actions,
  .overview-grid,
  .run-row,
  .event-row,
  .notification-row,
  .log-meta {
    grid-template-columns: 1fr;
  }
}
</style>
