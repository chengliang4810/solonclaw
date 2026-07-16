<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Button, Drawer, Select, Spin, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import {
  fetchCheckpointPreview,
  fetchSessionCheckpoints,
  fetchSessionRecap,
  fetchSessions,
  fetchSessionTrajectory,
  fetchSessionTree,
  rollbackCheckpoint,
  saveSessionTrajectory,
} from '@/api/solonclaw/sessions'
import {
  controlSubagent,
  controlRun,
  fetchActiveSubagents,
  fetchRecoverableRuns,
  fetchRunDetail,
  fetchSessionRuns,
  type AgentRun,
  type AgentRunEvent,
  type RunControlCommand,
  type RunRecovery,
  type SubagentRun,
  type ToolCall,
} from '@/api/solonclaw/runs'
import {
  runArtifactText,
  runBooleanLabel,
  runStatusLabel,
  runTimestampText,
} from '@/shared/runsDisplay'

const sessions = ref<any[]>([])
const runs = ref<AgentRun[]>([])
const events = ref<AgentRunEvent[]>([])
const tools = ref<ToolCall[]>([])
const subagents = ref<SubagentRun[]>([])
const activeSubagents = ref<SubagentRun[]>([])
const recoverableRuns = ref<AgentRun[]>([])
const subagentSpawnPaused = ref(false)
const recoveries = ref<RunRecovery[]>([])
const commands = ref<RunControlCommand[]>([])
const checkpoints = ref<any[]>([])
const tree = ref<any>(null)
const recap = ref<any>(null)
const trajectory = ref<any>(null)
const checkpointPreview = ref<any>(null)
const previewOpen = ref(false)
const selectedSessionId = ref('')
const selectedRunId = ref('')
const loading = ref(false)
const loadError = ref<string | null>(null)
const rollingBack = ref('')
const previewingCheckpoint = ref('')
const savingTrajectory = ref(false)
const runControlLoading = ref('')
const subagentControlLoading = ref('')
const subagentSpawnLoading = ref(false)
const recoverableLoading = ref(false)
const recoverableError = ref<string | null>(null)
const { t } = useI18n()

/** 标识最近一次会话详情请求，拒绝乱序响应。 */
let sessionDetailRequestId = 0
/** 标识最近一次运行详情请求，拒绝乱序响应。 */
let runDetailRequestId = 0
/** 标识最近一次活跃子代理请求。 */
let activeSubagentsRequestId = 0
/** 标识最近一次可恢复运行请求。 */
let recoverableRunsRequestId = 0
/** 标识最近一次运行控制请求。 */
let runControlRequestId = 0
/** 标识最近一次 Checkpoint 预览请求。 */
let checkpointPreviewRequestId = 0
/** 标识最近一次 Checkpoint 回滚请求。 */
let rollbackRequestId = 0
/** 标识最近一次子代理中断请求。 */
let subagentControlRequestId = 0
/** 标识最近一次子代理生成开关请求。 */
let subagentSpawnRequestId = 0
/** 记录组件是否已经卸载，阻止挂载链路继续发起请求。 */
let disposed = false

function errorMessage(err: unknown, fallback: string): string {
  return err instanceof Error ? err.message : String(err || fallback)
}

const sessionOptions = computed(() => sessions.value.map(session => ({
  label: session.title || session.preview || session.id,
  value: session.id,
})))

const selectedRun = computed(() => runs.value.find(run => run.run_id === selectedRunId.value))
const selectedRunActive = computed(() => {
  const status = (selectedRun.value?.status || '').toLowerCase()
  return Boolean(selectedRun.value && !['success', 'ok', 'failed', 'error', 'cancelled', 'stopped', 'finished'].includes(status))
})

/** 清空当前运行的所有明细，避免标题与旧事件错配。 */
function clearRunDetail(): void {
  events.value = []
  tools.value = []
  subagents.value = []
  recoveries.value = []
  commands.value = []
}

/** 清空当前会话的全部数据，失败时不保留上一会话内容。 */
function clearSessionDetail(): void {
  runs.value = []
  selectedRunId.value = ''
  clearRunDetail()
  tree.value = null
  checkpoints.value = []
  recap.value = null
  trajectory.value = null
  checkpointPreviewRequestId += 1
  checkpointPreview.value = null
  previewOpen.value = false
  previewingCheckpoint.value = ''
}

async function loadSessions() {
  if (disposed) return
  const loadedSessions = await fetchSessions(undefined, 200, false)
  if (disposed) return
  sessions.value = loadedSessions
  if (!selectedSessionId.value && sessions.value.length) {
    selectedSessionId.value = sessions.value[0].id
  }
}

async function loadSessionDetail(preferredRun?: AgentRun): Promise<boolean> {
  const sessionId = selectedSessionId.value
  if (!sessionId || disposed) return false
  const requestId = ++sessionDetailRequestId
  runDetailRequestId += 1
  loading.value = true
  loadError.value = null
  clearSessionDetail()
  void loadActiveSubagents()
  try {
    const [sessionRuns, loadedTree, loadedCheckpoints] = await Promise.all([
      fetchSessionRuns(sessionId, 30),
      fetchSessionTree(sessionId),
      fetchSessionCheckpoints(sessionId),
    ])
    if (requestId !== sessionDetailRequestId || sessionId !== selectedSessionId.value || disposed) return false
    const loadedRuns = preferredRun && !sessionRuns.some(run => run.run_id === preferredRun.run_id)
      ? [preferredRun, ...sessionRuns]
      : sessionRuns
    const runId = preferredRun?.run_id || loadedRuns[0]?.run_id || ''
    const [detail, loadedRecap, loadedTrajectory] = await Promise.all([
      runId ? fetchRunDetail(runId) : Promise.resolve(null),
      fetchSessionRecap(sessionId),
      fetchSessionTrajectory(sessionId),
    ])
    if (requestId !== sessionDetailRequestId || sessionId !== selectedSessionId.value || disposed) return false
    const detailRun = detail?.run
    if (detailRun && (detailRun.run_id !== runId || (detailRun.session_id && detailRun.session_id !== sessionId))) {
      throw new Error('Run detail does not match the requested session')
    }
    runs.value = detailRun
      ? loadedRuns.map(run => run.run_id === runId ? detailRun : run)
      : loadedRuns
    tree.value = loadedTree
    checkpoints.value = loadedCheckpoints
    selectedRunId.value = runId
    if (detail) {
      events.value = detail.events || []
      tools.value = detail.tools || []
      subagents.value = detail.subagents || []
      recoveries.value = detail.recoveries || []
      commands.value = detail.commands || []
    }
    recap.value = loadedRecap
    trajectory.value = loadedTrajectory
    return true
  } catch (err) {
    if (requestId === sessionDetailRequestId && sessionId === selectedSessionId.value && !disposed) {
      clearSessionDetail()
      console.error('Failed to load run session detail:', err)
      loadError.value = errorMessage(err, 'Failed to load run session detail')
    }
    return false
  } finally {
    if (requestId === sessionDetailRequestId && sessionId === selectedSessionId.value && !disposed) {
      loading.value = false
    }
  }
}

async function loadRunDetail(runId: string) {
  if (disposed) return
  const sessionId = selectedSessionId.value
  sessionDetailRequestId += 1
  loading.value = false
  selectedRunId.value = runId
  const requestId = ++runDetailRequestId
  loadError.value = null
  clearRunDetail()
  if (!runId) {
    return
  }
  try {
    const detail = await fetchRunDetail(runId)
    if (requestId !== runDetailRequestId
      || runId !== selectedRunId.value
      || sessionId !== selectedSessionId.value
      || disposed) return
    if (detail.run && (detail.run.run_id !== runId || (detail.run.session_id && detail.run.session_id !== sessionId))) {
      throw new Error('Run detail does not match the requested session')
    }
    if (detail.run) {
      runs.value = runs.value.map(run => run.run_id === runId ? detail.run : run)
    }
    events.value = detail.events || []
    tools.value = detail.tools || []
    subagents.value = detail.subagents || []
    recoveries.value = detail.recoveries || []
    commands.value = detail.commands || []
  } catch (err) {
    if (requestId === runDetailRequestId
      && runId === selectedRunId.value
      && sessionId === selectedSessionId.value
      && !disposed) {
      clearRunDetail()
      console.error('Failed to load run detail:', err)
      loadError.value = errorMessage(err, 'Failed to load run detail')
    }
  }
}

async function loadActiveSubagents() {
  if (disposed) return
  const requestId = ++activeSubagentsRequestId
  try {
    const state = await fetchActiveSubagents()
    if (requestId !== activeSubagentsRequestId || disposed) return
    activeSubagents.value = state.subagents
    subagentSpawnPaused.value = Boolean(state.spawn_paused)
  } catch (err) {
    if (requestId === activeSubagentsRequestId && !disposed) {
      console.error('Failed to load active subagents:', err)
    }
  }
}

async function loadRecoverableRuns() {
  if (disposed) return
  const requestId = ++recoverableRunsRequestId
  recoverableLoading.value = true
  recoverableError.value = null
  try {
    const loadedRuns = await fetchRecoverableRuns(20)
    if (requestId !== recoverableRunsRequestId || disposed) return
    recoverableRuns.value = loadedRuns
  } catch (err) {
    if (requestId === recoverableRunsRequestId && !disposed) {
      console.error('Failed to load recoverable runs:', err)
      recoverableError.value = errorMessage(err, 'Failed to load recoverable runs')
    }
  } finally {
    if (requestId === recoverableRunsRequestId && !disposed) recoverableLoading.value = false
  }
}

async function openRecoverableRun(run: AgentRun) {
  if (disposed) return
  if (run.session_id) {
    selectedSessionId.value = run.session_id
    await loadSessionDetail(run)
    return
  }
  if (!runs.value.some(item => item.run_id === run.run_id)) runs.value = [run, ...runs.value]
  await loadRunDetail(run.run_id)
}

async function handleRollback(id: string) {
  if (disposed) return
  const sessionId = selectedSessionId.value
  const selectionRequestId = runDetailRequestId
  const selectedRunSnapshot = selectedRun.value
  const requestId = ++rollbackRequestId
  rollingBack.value = id
  try {
    await rollbackCheckpoint(id)
    if (requestId !== rollbackRequestId
      || selectionRequestId !== runDetailRequestId
      || sessionId !== selectedSessionId.value
      || disposed) return
    await loadSessionDetail(selectedRunSnapshot)
  } finally {
    if (requestId === rollbackRequestId && !disposed) rollingBack.value = ''
  }
}

async function handleCheckpointPreview(id: string) {
  if (disposed) return
  const sessionId = selectedSessionId.value
  const requestId = ++checkpointPreviewRequestId
  previewingCheckpoint.value = id
  try {
    const preview = await fetchCheckpointPreview(id)
    if (requestId !== checkpointPreviewRequestId || sessionId !== selectedSessionId.value || disposed) return
    checkpointPreview.value = preview
    previewOpen.value = true
  } catch (err) {
    if (requestId === checkpointPreviewRequestId && sessionId === selectedSessionId.value && !disposed) {
      message.error(errorMessage(err, t('common.fetchFailed')))
    }
  } finally {
    if (requestId === checkpointPreviewRequestId && sessionId === selectedSessionId.value && !disposed) {
      previewingCheckpoint.value = ''
    }
  }
}

async function handleSaveTrajectory() {
  if (!selectedSessionId.value) return
  savingTrajectory.value = true
  try {
    await saveSessionTrajectory(selectedSessionId.value)
    message.success(t('runs.trajectorySaved'))
  } catch (err: any) {
    message.error(err.message || t('runs.trajectorySaveFailed'))
  } finally {
    savingTrajectory.value = false
  }
}

async function handleRunControl(command: 'stop' | 'cancel' | 'resume') {
  const run = selectedRun.value
  if (!run) return
  const runId = run.run_id
  const sessionId = selectedSessionId.value
  const selectionRequestId = runDetailRequestId
  const requestId = ++runControlRequestId
  runControlLoading.value = command
  try {
    await controlRun(runId, command)
    if (requestId !== runControlRequestId
      || selectionRequestId !== runDetailRequestId
      || sessionId !== selectedSessionId.value
      || runId !== selectedRunId.value
      || disposed) return
    message.success(t('runs.controlSent'))
    const reloaded = await loadSessionDetail(run)
    if (!reloaded || requestId !== runControlRequestId || disposed) return
    if (command === 'resume') {
      await loadRecoverableRuns()
    }
  } catch (err: any) {
    if (requestId === runControlRequestId && !disposed) {
      message.error(err.message || t('runs.controlFailed'))
    }
  } finally {
    if (requestId === runControlRequestId && !disposed) runControlLoading.value = ''
  }
}

async function handleSubagentInterrupt(subagentId: string) {
  if (disposed) return
  const requestId = ++subagentControlRequestId
  subagentControlLoading.value = subagentId
  try {
    await controlSubagent(subagentId, 'interrupt')
    if (requestId !== subagentControlRequestId || disposed) return
    message.success(t('runs.subagentInterruptSent'))
    await Promise.all([
      loadActiveSubagents(),
      selectedRunId.value ? loadRunDetail(selectedRunId.value) : Promise.resolve(),
    ])
  } catch (err: any) {
    if (requestId === subagentControlRequestId && !disposed) {
      message.error(err.message || t('runs.subagentInterruptFailed'))
    }
  } finally {
    if (requestId === subagentControlRequestId && !disposed) subagentControlLoading.value = ''
  }
}

async function handleSubagentSpawnToggle() {
  if (disposed) return
  const command = subagentSpawnPaused.value ? 'resume_spawn' : 'pause_spawn'
  const requestId = ++subagentSpawnRequestId
  subagentSpawnLoading.value = true
  try {
    await controlSubagent('_spawn', command)
    if (requestId !== subagentSpawnRequestId || disposed) return
    message.success(t(subagentSpawnPaused.value ? 'runs.subagentSpawnResumed' : 'runs.subagentSpawnPaused'))
    await loadActiveSubagents()
  } catch (err: any) {
    if (requestId === subagentSpawnRequestId && !disposed) {
      message.error(err.message || t('runs.subagentSpawnControlFailed'))
    }
  } finally {
    if (requestId === subagentSpawnRequestId && !disposed) subagentSpawnLoading.value = false
  }
}

onMounted(async () => {
  await loadSessions()
  await loadSessionDetail()
})

onUnmounted(() => {
  disposed = true
  sessionDetailRequestId += 1
  runDetailRequestId += 1
  activeSubagentsRequestId += 1
  recoverableRunsRequestId += 1
  runControlRequestId += 1
  checkpointPreviewRequestId += 1
  rollbackRequestId += 1
  subagentControlRequestId += 1
  subagentSpawnRequestId += 1
})
</script>

<template>
  <div class="runs-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('runs.title') }}</h2>
        <p class="header-subtitle">{{ t('runs.description') }}</p>
      </div>
      <div class="header-actions">
        <Select v-model:value="selectedSessionId" :options="sessionOptions" size="small" class="session-select" @update:value="loadSessionDetail()" />
        <Button size="small" :loading="loading" @click="loadSessionDetail()">{{ t('common.refresh') }}</Button>
      </div>
    </header>

    <Spin :spinning="loading">
      <main class="runs-layout">
        <section class="panel">
          <h3>{{ t('runs.runList') }}</h3>
          <div v-if="loadError" class="runs-load-error">
            <strong>{{ t('common.fetchFailed') }}</strong>
            <span>{{ loadError }}</span>
          </div>
          <button v-for="run in runs" :key="run.run_id" class="run-row" :class="{ active: run.run_id === selectedRunId }" @click="loadRunDetail(run.run_id)">
            <span class="run-status" :class="run.status">{{ runStatusLabel(run.status, t) }}</span>
            <span>{{ run.provider || '-' }}/{{ run.model || '-' }}</span>
            <span>{{ t('runs.attempts', { count: run.attempts }) }}</span>
            <small>{{ runTimestampText(run.started_at) }}</small>
            <p>{{ run.final_reply_preview || run.input_preview || run.error }}</p>
          </button>
          <div v-if="runs.length === 0 && !loadError" class="empty">{{ t('runs.noRuns') }}</div>
        </section>

        <section class="panel">
          <h3>{{ t('runs.runDetail') }}</h3>
          <div v-if="selectedRun" class="run-detail">
            <div class="detail-line">
              <span>{{ t('runs.runId') }}</span>
              <code>{{ selectedRun.run_id }}</code>
            </div>
            <div class="detail-line">
              <span>{{ t('runs.sessionId') }}</span>
              <code>{{ selectedRun.session_id }}</code>
            </div>
            <div v-if="selectedRun.exit_reason" class="detail-line">
              <span>{{ t('runs.exitReason') }}</span>
              <code>{{ selectedRun.exit_reason }}</code>
            </div>
            <div class="detail-metrics">
              <span>{{ t('runs.toolCount', { count: selectedRun.tool_call_count || tools.length }) }}</span>
              <span>{{ t('runs.tokenCount', { count: selectedRun.total_tokens || 0 }) }}</span>
            </div>
            <div class="run-actions">
              <Button v-if="selectedRunActive" size="small" :loading="runControlLoading === 'stop'" @click="handleRunControl('stop')">
                {{ t('runs.stopRun') }}
              </Button>
              <Button v-if="selectedRunActive" size="small" danger :loading="runControlLoading === 'cancel'" @click="handleRunControl('cancel')">
                {{ t('runs.cancelRun') }}
              </Button>
              <Button v-if="selectedRun.recoverable" size="small" type="primary" :loading="runControlLoading === 'resume'" @click="handleRunControl('resume')">
                {{ t('runs.resumeRun') }}
              </Button>
            </div>
          </div>

          <h3>{{ t('runs.events') }}</h3>
          <div v-for="event in events" :key="event.event_id" class="event-row">
            <span class="event-type">{{ event.event_type }}</span>
            <span>{{ event.summary }}</span>
            <small>#{{ event.attempt_no }} · {{ runTimestampText(event.created_at) }}</small>
          </div>
          <div v-if="events.length === 0" class="empty">{{ t('runs.noEvents') }}</div>

          <h3 class="section-title">{{ t('runs.tools') }}</h3>
          <div v-for="tool in tools" :key="tool.tool_call_id" class="tool-row">
            <div class="tool-header">
              <span class="event-type">{{ tool.tool_name }}</span>
              <span class="run-status" :class="tool.status">{{ runStatusLabel(tool.status, t) }}</span>
              <small>{{ tool.duration_ms }}ms</small>
            </div>
            <div class="detail-line">
              <span>{{ t('runs.toolCallId') }}</span>
              <code>{{ tool.tool_call_id }}</code>
            </div>
            <div class="audit-grid">
              <span>{{ t('runs.readOnly') }}: {{ runBooleanLabel(tool.read_only, t) }}</span>
              <span>{{ t('runs.sideEffecting') }}: {{ runBooleanLabel(tool.side_effecting, t) }}</span>
              <span>{{ t('runs.interruptible') }}: {{ runBooleanLabel(tool.interruptible, t) }}</span>
              <span>{{ t('runs.resultIndexable') }}: {{ runBooleanLabel(tool.result_indexable, t) }}</span>
              <span>{{ t('runs.executionPolicy') }}: {{ tool.execution_policy || '-' }}</span>
              <span>{{ t('runs.resultSizeBytes') }}: {{ tool.result_size_bytes ?? 0 }}</span>
            </div>
            <div class="preview-block">
              <span>{{ t('runs.argsPreview') }}</span>
              <pre>{{ tool.args_preview || '{}' }}</pre>
            </div>
            <div class="preview-block">
              <span>{{ t('runs.resultPreview') }}</span>
              <pre>{{ tool.result_preview || tool.error || '-' }}</pre>
            </div>
          </div>
          <div v-if="selectedRunId && tools.length === 0" class="empty">{{ t('runs.noTools') }}</div>

          <h3 class="section-title">{{ t('runs.subagents') }}</h3>
          <div v-for="subagent in subagents" :key="subagent.subagent_id" class="event-row">
            <div class="tool-header">
              <span class="event-type">{{ subagent.name || subagent.subagent_id }}</span>
              <span class="run-status" :class="subagent.status">{{ runStatusLabel(subagent.status, t) }}</span>
              <small>{{ t('runs.subagentDepth', { depth: subagent.depth }) }}</small>
            </div>
            <p>{{ subagent.goal_preview || subagent.goal || subagent.error || '-' }}</p>
            <small>{{ runTimestampText(subagent.started_at) }}</small>
          </div>
          <div v-if="selectedRunId && subagents.length === 0" class="empty">{{ t('runs.noSubagents') }}</div>

          <h3 class="section-title">{{ t('runs.recoveries') }}</h3>
          <div v-for="recovery in recoveries" :key="recovery.recovery_id" class="event-row">
            <span class="event-type">{{ recovery.recovery_type }}</span>
            <span>{{ recovery.summary || recovery.status }}</span>
            <small>{{ runTimestampText(recovery.created_at) }}</small>
          </div>
          <div v-if="selectedRunId && recoveries.length === 0" class="empty">{{ t('runs.noRecoveries') }}</div>

          <h3 class="section-title">{{ t('runs.commands') }}</h3>
          <div v-for="command in commands" :key="command.command_id" class="event-row">
            <span class="event-type">{{ command.command }}</span>
            <span>{{ runStatusLabel(command.status, t) }}</span>
            <small>{{ runTimestampText(command.created_at) }}</small>
          </div>
          <div v-if="selectedRunId && commands.length === 0" class="empty">{{ t('runs.noCommands') }}</div>
        </section>

        <section class="panel side-panel">
          <div class="section-heading">
            <h3>{{ t('runs.recoverableRuns') }}</h3>
            <Button size="small" :loading="recoverableLoading" @click="loadRecoverableRuns">{{ t('runs.loadRecoverableRuns') }}</Button>
          </div>
          <div v-if="recoverableError" class="runs-load-error compact-error">
            <strong>{{ t('common.fetchFailed') }}</strong>
            <span>{{ recoverableError }}</span>
          </div>
          <button
            v-for="run in recoverableRuns"
            :key="run.run_id"
            class="run-row recoverable-row"
            :class="{ active: run.run_id === selectedRunId }"
            @click="openRecoverableRun(run)"
          >
            <span class="run-status" :class="run.status">{{ runStatusLabel(run.status, t) }}</span>
            <span>{{ run.provider || '-' }}/{{ run.model || '-' }}</span>
            <small>{{ runTimestampText(run.started_at) }}</small>
            <p>{{ run.recovery_hint || run.error || run.input_preview || run.run_id }}</p>
          </button>
          <div v-if="recoverableRuns.length === 0 && !recoverableError" class="empty compact">{{ t('runs.noRecoverableRuns') }}</div>

          <div class="section-heading">
            <h3>{{ t('runs.activeSubagents') }}</h3>
            <Button size="small" :loading="subagentSpawnLoading" @click="handleSubagentSpawnToggle">
              {{ t(subagentSpawnPaused ? 'runs.resumeSubagentSpawn' : 'runs.pauseSubagentSpawn') }}
            </Button>
          </div>
          <div v-if="subagentSpawnPaused" class="empty compact">{{ t('runs.subagentSpawnPausedHint') }}</div>
          <div v-for="subagent in activeSubagents" :key="subagent.subagent_id" class="mini-row">
            <span>{{ subagent.name || subagent.subagent_id }}</span>
            <small>{{ runStatusLabel(subagent.status, t) }} · {{ subagent.goal_preview || subagent.goal || '-' }}</small>
            <Button size="small" danger :loading="subagentControlLoading === subagent.subagent_id" @click="handleSubagentInterrupt(subagent.subagent_id)">
              {{ t('runs.interruptSubagent') }}
            </Button>
          </div>
          <div v-if="activeSubagents.length === 0" class="empty compact">{{ t('runs.noActiveSubagents') }}</div>
          <h3>{{ t('runs.sessionRecap') }}</h3>
          <pre class="artifact-block">{{ runArtifactText(recap, t) }}</pre>
          <div class="section-heading">
            <h3>{{ t('runs.sessionTrajectory') }}</h3>
            <Button size="small" :loading="savingTrajectory" :disabled="!selectedSessionId" @click="handleSaveTrajectory">{{ t('runs.saveTrajectory') }}</Button>
          </div>
          <pre class="artifact-block">{{ runArtifactText(trajectory, t) }}</pre>
          <h3>{{ t('runs.sessionTree') }}</h3>
          <div v-for="node in tree?.nodes || []" :key="node.id" class="mini-row">
            <span>{{ node.branch_name || t('runs.mainBranch') }}</span>
            <small>{{ node.id }}</small>
          </div>
          <h3>{{ t('runs.checkpoints') }}</h3>
          <div v-for="checkpoint in checkpoints" :key="checkpoint.checkpoint_id" class="mini-row">
            <span>{{ runTimestampText(checkpoint.created_at) }}</span>
            <Button size="small" type="default" :loading="previewingCheckpoint === checkpoint.checkpoint_id" @click="handleCheckpointPreview(checkpoint.checkpoint_id)">{{ t('runs.previewCheckpoint') }}</Button>
            <Button size="small" type="default" :loading="rollingBack === checkpoint.checkpoint_id" @click="handleRollback(checkpoint.checkpoint_id)">{{ t('runs.rollback') }}</Button>
          </div>
        </section>
      </main>
    </Spin>

    <Drawer v-model:open="previewOpen" placement="right" :style="{ width: '560px' }" :title="t('runs.checkpointPreview')">
      <pre class="artifact-block">{{ runArtifactText(checkpointPreview, t) }}</pre>
    </Drawer>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.runs-view {
  height: calc(100 * var(--vh));
  display: flex;
  flex-direction: column;
}

.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  min-width: 0;
  max-width: 100%;
}

.session-select {
  width: 320px;
  max-width: 100%;
  min-width: 0;
}

.runs-layout {
  display: grid;
  grid-template-columns: minmax(280px, 0.9fr) minmax(360px, 1.2fr) minmax(260px, 0.8fr);
  gap: 16px;
  padding: 20px;
  min-height: 0;
  overflow-y: auto;
}

.panel {
  min-height: 520px;
  min-width: 0;
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  background: $bg-primary;
  overflow: auto;
  padding: 14px;
}

h3 {
  margin: 0 0 12px;
  font-size: 14px;
}

.section-title {
  margin-top: 18px;
}

.section-heading {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;

  h3 {
    margin: 0;
  }
}

.run-row,
.event-row,
.tool-row,
.mini-row {
  width: 100%;
  min-width: 0;
  border: 1px solid rgba($border-color, 0.7);
  border-radius: $radius-sm;
  background: transparent;
  color: $text-primary;
  text-align: left;
  padding: 10px;
  margin-bottom: 8px;
}

.run-row {
  display: grid;
  gap: 5px;
  cursor: pointer;
}

.run-row.active {
  border-color: $accent-primary;
  background: rgba(var(--accent-primary-rgb), 0.08);
}

.run-row p,
.event-row small,
.mini-row small {
  margin: 0;
  color: $text-muted;
  overflow-wrap: anywhere;
}

.event-row,
.tool-row,
.mini-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.run-detail {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  margin-bottom: 14px;
  border: 1px solid rgba($border-color, 0.7);
  border-radius: $radius-sm;
  background: rgba($bg-secondary, 0.45);
}

.detail-line {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
  color: $text-muted;
}

.detail-line code {
  color: $text-primary;
  font-family: $font-code;
  overflow-wrap: anywhere;
}

.detail-metrics,
.run-actions,
.tool-header {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}

.detail-metrics span,
.tool-header small {
  color: $text-muted;
}

.audit-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(132px, 1fr));
  gap: 6px;
  color: $text-muted;
  font-size: 12px;
}

.preview-block {
  display: grid;
  gap: 4px;
  color: $text-muted;
}

.preview-block pre {
  margin: 0;
  padding: 8px;
  max-height: 160px;
  overflow: auto;
  border-radius: $radius-sm;
  background: rgba($bg-secondary, 0.72);
  color: $text-primary;
  font-family: $font-code;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.artifact-block {
  margin: 0 0 16px;
  padding: 8px;
  max-height: 220px;
  overflow: auto;
  border-radius: $radius-sm;
  background: rgba($bg-secondary, 0.72);
  color: $text-primary;
  font-family: $font-code;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.run-status,
.event-type {
  font-family: $font-code;
  font-size: 12px;
  color: $accent-primary;
}

.empty {
  color: $text-muted;
  padding: 24px 0;
  text-align: center;
}

.empty.compact {
  padding: 8px 0 16px;
}

.runs-load-error {
  display: grid;
  gap: 4px;
  margin-bottom: 8px;
  padding: 10px 12px;
  border: 1px solid rgba(var(--error-rgb), 0.28);
  border-radius: $radius-sm;
  background: rgba(var(--error-rgb), 0.06);
  color: $error;
  font-size: 13px;

  span {
    overflow-wrap: anywhere;
  }
}

.compact-error {
  padding: 8px 10px;
}

@media (max-width: 1100px) {
  .runs-layout {
    grid-template-columns: 1fr;
  }

  .panel {
    min-height: 320px;
  }
}

@media (max-width: $breakpoint-mobile) {
  .page-header {
    align-items: flex-start;
    flex-wrap: wrap;
    gap: 10px;

    > div:first-child {
      min-width: 0;
    }
  }

  .header-actions {
    flex: 1 1 100%;
    width: 100%;
    align-items: stretch;
  }

  .session-select {
    flex: 1 1 100%;
    width: 100%;
  }

  .session-select :deep(.ant-select-selection-item) {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .runs-layout {
    padding: 12px;
    gap: 12px;
  }
}
</style>
