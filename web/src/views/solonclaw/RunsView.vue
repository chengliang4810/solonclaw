<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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
const rollingBack = ref('')
const previewingCheckpoint = ref('')
const savingTrajectory = ref(false)
const runControlLoading = ref('')
const subagentControlLoading = ref('')
const subagentSpawnLoading = ref(false)
const { t } = useI18n()

const sessionOptions = computed(() => sessions.value.map(session => ({
  label: session.title || session.preview || session.id,
  value: session.id,
})))

const selectedRun = computed(() => runs.value.find(run => run.run_id === selectedRunId.value))
const selectedRunActive = computed(() => {
  const status = (selectedRun.value?.status || '').toLowerCase()
  return Boolean(selectedRun.value && !['success', 'ok', 'failed', 'error', 'cancelled', 'stopped', 'finished'].includes(status))
})

async function loadSessions() {
  sessions.value = await fetchSessions(undefined, 200)
  if (!selectedSessionId.value && sessions.value.length) {
    selectedSessionId.value = sessions.value[0].id
  }
}

async function loadSessionDetail() {
  if (!selectedSessionId.value) return
  loading.value = true
  try {
    const [loadedRuns, loadedTree, loadedCheckpoints] = await Promise.all([
      fetchSessionRuns(selectedSessionId.value, 30),
      fetchSessionTree(selectedSessionId.value),
      fetchSessionCheckpoints(selectedSessionId.value),
    ])
    runs.value = loadedRuns
    tree.value = loadedTree
    checkpoints.value = loadedCheckpoints
    await loadActiveSubagents()
    selectedRunId.value = loadedRuns[0]?.run_id || ''
    if (selectedRunId.value) {
      const detail = await fetchRunDetail(selectedRunId.value)
      events.value = detail.events || []
      tools.value = detail.tools || []
      subagents.value = detail.subagents || []
      recoveries.value = detail.recoveries || []
      commands.value = detail.commands || []
    } else {
      events.value = []
      tools.value = []
      subagents.value = []
      recoveries.value = []
      commands.value = []
    }
    await loadSessionArtifacts()
  } finally {
    loading.value = false
  }
}

async function loadSessionArtifacts() {
  if (!selectedSessionId.value) return
  const [loadedRecap, loadedTrajectory] = await Promise.all([
    fetchSessionRecap(selectedSessionId.value),
    fetchSessionTrajectory(selectedSessionId.value),
  ])
  recap.value = loadedRecap
  trajectory.value = loadedTrajectory
}

async function loadRunDetail(runId: string) {
  selectedRunId.value = runId
  if (!runId) {
    events.value = []
    tools.value = []
    subagents.value = []
    recoveries.value = []
    commands.value = []
    return
  }
  const detail = await fetchRunDetail(runId)
  events.value = detail.events || []
  tools.value = detail.tools || []
  subagents.value = detail.subagents || []
  recoveries.value = detail.recoveries || []
  commands.value = detail.commands || []
}

async function loadActiveSubagents() {
  const state = await fetchActiveSubagents()
  activeSubagents.value = state.subagents
  subagentSpawnPaused.value = Boolean(state.spawn_paused)
}

async function handleRollback(id: string) {
  rollingBack.value = id
  try {
    await rollbackCheckpoint(id)
    await loadSessionDetail()
  } finally {
    rollingBack.value = ''
  }
}

async function handleCheckpointPreview(id: string) {
  previewingCheckpoint.value = id
  try {
    checkpointPreview.value = await fetchCheckpointPreview(id)
    previewOpen.value = true
  } finally {
    previewingCheckpoint.value = ''
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
  const runId = selectedRun.value?.run_id
  if (!runId) return
  runControlLoading.value = command
  try {
    await controlRun(runId, command)
    message.success(t('runs.controlSent'))
    await loadSessionDetail()
  } catch (err: any) {
    message.error(err.message || t('runs.controlFailed'))
  } finally {
    runControlLoading.value = ''
  }
}

async function handleSubagentInterrupt(subagentId: string) {
  subagentControlLoading.value = subagentId
  try {
    await controlSubagent(subagentId, 'interrupt')
    message.success(t('runs.subagentInterruptSent'))
    await Promise.all([
      loadActiveSubagents(),
      selectedRunId.value ? loadRunDetail(selectedRunId.value) : Promise.resolve(),
    ])
  } catch (err: any) {
    message.error(err.message || t('runs.subagentInterruptFailed'))
  } finally {
    subagentControlLoading.value = ''
  }
}

async function handleSubagentSpawnToggle() {
  const command = subagentSpawnPaused.value ? 'resume_spawn' : 'pause_spawn'
  subagentSpawnLoading.value = true
  try {
    await controlSubagent('_spawn', command)
    message.success(t(subagentSpawnPaused.value ? 'runs.subagentSpawnResumed' : 'runs.subagentSpawnPaused'))
    await loadActiveSubagents()
  } catch (err: any) {
    message.error(err.message || t('runs.subagentSpawnControlFailed'))
  } finally {
    subagentSpawnLoading.value = false
  }
}

onMounted(async () => {
  await loadSessions()
  await loadSessionDetail()
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
        <Select v-model:value="selectedSessionId" :options="sessionOptions" size="small" class="session-select" @update:value="loadSessionDetail" />
        <Button size="small" :loading="loading" @click="loadSessionDetail">{{ t('common.refresh') }}</Button>
      </div>
    </header>

    <Spin :spinning="loading">
      <main class="runs-layout">
        <section class="panel">
          <h3>{{ t('runs.runList') }}</h3>
          <button v-for="run in runs" :key="run.run_id" class="run-row" :class="{ active: run.run_id === selectedRunId }" @click="loadRunDetail(run.run_id)">
            <span class="run-status" :class="run.status">{{ runStatusLabel(run.status, t) }}</span>
            <span>{{ run.provider || '-' }}/{{ run.model || '-' }}</span>
            <span>{{ t('runs.attempts', { count: run.attempts }) }}</span>
            <small>{{ runTimestampText(run.started_at) }}</small>
            <p>{{ run.final_reply_preview || run.input_preview || run.error }}</p>
          </button>
          <div v-if="runs.length === 0" class="empty">{{ t('runs.noRuns') }}</div>
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
            <Button size="small" :loading="savingTrajectory" @click="handleSaveTrajectory">{{ t('runs.saveTrajectory') }}</Button>
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
