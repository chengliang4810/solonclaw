<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NInput, NInputNumber, NModal, NSelect, NSpin, useMessage } from 'naive-ui'
import {
  addKanbanComment,
  createKanbanBoard,
  createKanbanTask,
  fetchKanbanBoards,
  fetchKanbanTask,
  fetchKanbanTasks,
  kanbanStatuses,
  moveKanbanTask,
  switchKanbanBoard,
  updateKanbanTask,
  type KanbanBoard,
  type KanbanStatus,
  type KanbanTask,
} from '@/api/hermes/kanban'

const message = useMessage()
const loading = ref(false)
const boards = ref<KanbanBoard[]>([])
const tasks = ref<KanbanTask[]>([])
const activeBoard = ref('')
const showTaskModal = ref(false)
const showBoardModal = ref(false)
const selectedTask = ref<KanbanTask | null>(null)
const commentText = ref('')
const taskForm = ref({
  title: '',
  body: '',
  assignee: '',
  status: 'todo' as KanbanStatus,
  priority: 0,
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

const taskColumns = computed(() => {
  const grouped = new Map<KanbanStatus, KanbanTask[]>()
  visibleStatuses.forEach(status => grouped.set(status, []))
  tasks.value.forEach(task => {
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
    tasks.value = await fetchKanbanTasks(activeBoard.value)
  } finally {
    loading.value = false
  }
}

async function reloadTasks() {
  tasks.value = await fetchKanbanTasks(activeBoard.value)
  boards.value = await fetchKanbanBoards()
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
    tenant: '',
  }
  showTaskModal.value = true
}

async function openTask(task: KanbanTask) {
  selectedTask.value = await fetchKanbanTask(task.id)
  taskForm.value = {
    title: selectedTask.value.title,
    body: selectedTask.value.body || '',
    assignee: selectedTask.value.assignee || '',
    status: selectedTask.value.status,
    priority: selectedTask.value.priority || 0,
    tenant: selectedTask.value.tenant || '',
  }
  commentText.value = ''
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
  commentText.value = ''
  await reloadTasks()
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
</script>

<template>
  <div class="kanban-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">任务看板</h2>
        <p class="header-subtitle">Hermes 风格多看板任务协作，使用本地 SQLite 持久化。</p>
      </div>
      <div class="header-actions">
        <NSelect
          v-model:value="activeBoard"
          :options="boardOptions"
          size="small"
          class="board-select"
          @update:value="handleBoardChange"
        />
        <NButton size="small" @click="showBoardModal = true">新建看板</NButton>
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
                <span class="priority">P{{ task.priority || 0 }}</span>
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
            <span>执行人</span>
            <NInput v-model:value="taskForm.assignee" placeholder="agent/profile/user" />
          </label>
          <label>
            <span>租户/命名空间</span>
            <NInput v-model:value="taskForm.tenant" placeholder="可选" />
          </label>
        </div>
        <div v-if="selectedTask" class="comments">
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

.board-select {
  width: 220px;
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

.comments-title {
  color: $text-primary;
  font-weight: 600;
  margin-bottom: 8px;
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

  .kanban-board {
    grid-template-columns: repeat(6, 240px);
    height: calc(100 * var(--vh) - 132px);
  }

  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
