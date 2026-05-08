<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NDrawer, NDrawerContent, NInput, NModal, NSpin, NTooltip, useMessage } from 'naive-ui'
import type { Job, JobRun } from '@/api/jimuqu/jobs'
import { useJobsStore } from '@/stores/jimuqu/jobs'
import { useI18n } from 'vue-i18n'

const props = defineProps<{ job: Job }>()
const emit = defineEmits<{
  edit: [jobId: string]
}>()

const { t } = useI18n()
const jobsStore = useJobsStore()
const message = useMessage()
const showRuns = ref(false)
const showPauseModal = ref(false)
const pauseReason = ref('')
const runsLoading = ref(false)
const runs = ref<JobRun[]>([])

const jobId = computed(() => props.job.job_id || props.job.id)

const statusLabel = computed(() => {
  if (props.job.state === 'running') return t('jobs.status.running')
  if (props.job.state === 'paused') return t('jobs.status.paused')
  if (!props.job.enabled) return t('jobs.status.disabled')
  return t('jobs.status.scheduled')
})

const statusType = computed(() => {
  if (props.job.state === 'running') return 'info' as const
  if (props.job.state === 'paused') return 'warning' as const
  if (!props.job.enabled) return 'error' as const
  return 'success' as const
})

const scheduleExpr = computed(() => {
  const s = props.job.schedule
  if (typeof s === 'string') return s
  return s?.display || s?.expr || props.job.schedule_display || '—'
})

const scheduleKind = computed(() => {
  const s = props.job.schedule
  if (typeof s === 'object' && s?.kind) return s.kind
  const expr = scheduleExpr.value.trim()
  if (/^every\s+\d+/i.test(expr)) return 'interval'
  if (/^\d+\s*(m|min|minute|minutes|h|hr|hrs|hour|hours|d|day|days)$/i.test(expr)) return 'once'
  if (/^\d{4}-\d{2}-\d{2}T/.test(expr)) return 'once'
  return 'cron'
})

const scheduleKindLabel = computed(() => {
  if (scheduleKind.value === 'interval') return t('jobs.scheduleKindInterval')
  if (scheduleKind.value === 'once') return t('jobs.scheduleKindOnce')
  return t('jobs.scheduleKindCron')
})

const formatTime = (t?: string | null) => {
  if (!t) return '—'
  return new Date(t).toLocaleString()
}

function formatDuration(durationMs?: number | null) {
  if (durationMs === null || durationMs === undefined) return '—'
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

const jobBadges = computed(() => {
  const badges: string[] = []
  if (props.job.no_agent) badges.push(t('jobs.badge.noAgent'))
  if (props.job.script) badges.push(t('jobs.badge.script'))
  if (props.job.wrap_response) badges.push(t('jobs.badge.wrapResponse'))
  if (props.job.skills?.length) badges.push(t('jobs.badge.skills', { count: props.job.skills.length }))
  if (props.job.context_from?.length) badges.push(t('jobs.badge.context', { count: props.job.context_from.length }))
  if (props.job.enabled_toolsets?.length) badges.push(t('jobs.badge.toolsets', { count: props.job.enabled_toolsets.length }))
  if (props.job.model) badges.push(props.job.provider ? `${props.job.provider}:${props.job.model}` : props.job.model)
  return badges
})

const deliverDetail = computed(() => {
  const parts = [props.job.deliver || 'local']
  if (props.job.origin?.platform) parts.push(props.job.origin.platform)
  if (props.job.deliver_chat_id) parts.push(props.job.deliver_chat_id)
  if (props.job.deliver_thread_id) parts.push(`#${props.job.deliver_thread_id}`)
  return parts.join(' · ')
})

const modelDetail = computed(() => {
  const parts = [props.job.provider, props.job.model, props.job.base_url].filter(Boolean)
  return parts.length ? parts.join(' · ') : '—'
})

function listDetail(values?: string[] | null) {
  return values && values.length ? values.join(', ') : '—'
}

function boolDetail(value: boolean) {
  return value ? t('jobs.detail.yes') : t('jobs.detail.no')
}

async function handlePause() {
  try {
    await jobsStore.pauseJob(jobId.value, pauseReason.value)
    showPauseModal.value = false
    pauseReason.value = ''
    message.success(t('jobs.jobPaused'))
  } catch (e: any) {
    message.error(e.message)
  }
}

function openPauseModal() {
  pauseReason.value = props.job.paused_reason || ''
  showPauseModal.value = true
}

async function handleResume() {
  try {
    await jobsStore.resumeJob(jobId.value)
    message.success(t('jobs.jobResumed'))
  } catch (e: any) {
    message.error(e.message)
  }
}

async function handleRun() {
  try {
    await jobsStore.runJob(jobId.value)
    if (showRuns.value) {
      await refreshRuns()
    }
    message.info(t('jobs.jobTriggered'))
  } catch (e: any) {
    message.error(e.message)
  }
}

async function refreshRuns() {
  runsLoading.value = true
  try {
    runs.value = await jobsStore.fetchJobRuns(jobId.value, 20)
  } catch (e: any) {
    message.error(e.message)
  } finally {
    runsLoading.value = false
  }
}

async function openRuns() {
  showRuns.value = true
  await refreshRuns()
}

async function handleDelete() {
  try {
    await jobsStore.deleteJob(jobId.value)
    message.success(t('jobs.jobDeleted'))
  } catch (e: any) {
    message.error(e.message)
  }
}
</script>

<template>
  <div class="job-card">
    <div class="card-header">
      <h3 class="job-name">{{ job.name }}</h3>
      <span class="status-badge" :class="statusType">{{ statusLabel }}</span>
    </div>

    <div class="card-body">
      <div class="info-row">
        <span class="info-label">{{ t('jobs.info.schedule') }}</span>
        <span class="schedule-value">
          <span class="schedule-kind">{{ scheduleKindLabel }}</span>
          <code class="info-value mono">{{ scheduleExpr }}</code>
        </span>
      </div>
      <div class="info-row">
        <span class="info-label">{{ t('jobs.info.lastRun') }}</span>
        <span class="info-value">
          {{ formatTime(job.last_run_at) }}
          <span v-if="job.last_status" class="run-status" :class="{ ok: job.last_status === 'ok', err: job.last_status !== 'ok' }">
            {{ job.last_status === 'ok' ? t('common.ok') : job.last_status }}
          </span>
        </span>
      </div>
      <div class="info-row">
        <span class="info-label">{{ t('jobs.info.nextRun') }}</span>
        <span class="info-value">{{ formatTime(job.next_run_at) }}</span>
      </div>
      <div class="info-row">
        <span class="info-label">{{ t('jobs.info.deliver') }}</span>
        <span class="info-value">{{ deliverDetail }}</span>
      </div>
      <div v-if="job.repeat" class="info-row">
        <span class="info-label">{{ t('jobs.info.repeat') }}</span>
        <span class="info-value">
          <template v-if="typeof job.repeat === 'string'">{{ job.repeat }}</template>
          <template v-else>{{ job.repeat.completed }} / {{ job.repeat.times ?? '∞' }}</template>
        </span>
      </div>
      <div v-if="jobBadges.length" class="job-badges">
        <span v-for="badge in jobBadges" :key="badge" class="job-badge">{{ badge }}</span>
      </div>
      <div v-if="job.last_error || job.last_delivery_error" class="error-line">
        {{ job.last_error || job.last_delivery_error }}
      </div>
      <div v-if="job.paused_reason" class="pause-line">
        {{ t('jobs.pauseReason') }}：{{ job.paused_reason }}
      </div>
      <div v-if="job.last_output" class="output-preview">
        {{ job.last_output }}
      </div>
    </div>

    <div class="card-actions">
      <NTooltip v-if="job.state !== 'paused' && job.enabled">
        <template #trigger>
          <NButton size="tiny" quaternary @click="openPauseModal">{{ t('jobs.action.pause') }}</NButton>
        </template>
        {{ t('jobs.action.pauseJob') }}
      </NTooltip>
      <NTooltip v-else-if="job.state === 'paused'">
        <template #trigger>
          <NButton size="tiny" quaternary @click="handleResume">{{ t('jobs.action.resume') }}</NButton>
        </template>
        {{ t('jobs.action.resumeJob') }}
      </NTooltip>
      <NTooltip>
        <template #trigger>
          <NButton size="tiny" quaternary @click="handleRun">{{ t('jobs.action.runNow') }}</NButton>
        </template>
        {{ t('jobs.action.triggerImmediately') }}
      </NTooltip>
      <NButton size="tiny" quaternary @click="openRuns">{{ t('jobs.action.history') }}</NButton>
      <NButton size="tiny" quaternary @click="emit('edit', jobId)">{{ t('common.edit') }}</NButton>
      <NButton size="tiny" quaternary type="error" @click="handleDelete">{{ t('common.delete') }}</NButton>
    </div>

    <NModal
      v-model:show="showPauseModal"
      preset="dialog"
      :title="t('jobs.pauseTitle')"
      :positive-text="t('jobs.action.pause')"
      :negative-text="t('common.cancel')"
      @positive-click="handlePause"
    >
      <NInput
        v-model:value="pauseReason"
        type="textarea"
        :rows="3"
        :maxlength="300"
        show-count
        :placeholder="t('jobs.pauseReasonPlaceholder')"
      />
    </NModal>

    <NDrawer v-model:show="showRuns" placement="right" :width="520">
      <NDrawerContent :title="t('jobs.historyTitle', { name: job.name })" closable>
        <section class="detail-section">
          <h4>{{ t('jobs.detail.config') }}</h4>
          <div class="detail-grid">
            <span>{{ t('jobs.detail.skills') }}</span>
            <code>{{ listDetail(job.skills) }}</code>
            <span>{{ t('jobs.detail.deliver') }}</span>
            <code>{{ deliverDetail }}</code>
            <span>{{ t('jobs.detail.wrapResponse') }}</span>
            <code>{{ boolDetail(job.wrap_response) }}</code>
            <span>{{ t('jobs.detail.script') }}</span>
            <code>{{ job.script || '—' }}</code>
            <span>{{ t('jobs.detail.noAgent') }}</span>
            <code>{{ boolDetail(job.no_agent) }}</code>
            <span>{{ t('jobs.detail.workdir') }}</span>
            <code>{{ job.workdir || '—' }}</code>
            <span>{{ t('jobs.detail.contextFrom') }}</span>
            <code>{{ listDetail(job.context_from) }}</code>
            <span>{{ t('jobs.detail.enabledToolsets') }}</span>
            <code>{{ listDetail(job.enabled_toolsets) }}</code>
            <span>{{ t('jobs.detail.model') }}</span>
            <code>{{ modelDetail }}</code>
          </div>
        </section>
        <NSpin :show="runsLoading">
          <div v-if="runs.length === 0" class="empty-runs">{{ t('jobs.noHistory') }}</div>
          <div v-else class="run-list">
            <div v-for="run in runs" :key="run.run_id" class="run-item">
              <div class="run-head">
                <span class="run-status" :class="{ ok: run.status === 'ok', err: run.status && run.status !== 'ok' }">
                  {{ run.status || '—' }}
                </span>
                <span class="run-time">{{ formatTime(run.started_at) }}</span>
              </div>
              <div class="run-meta">
                {{ t('jobs.historyTrigger') }} {{ run.trigger || 'scheduled' }}
                <template v-if="run.attempt"> · {{ t('jobs.historyAttempt') }} {{ run.attempt }}</template>
                <template v-if="run.finished !== undefined">
                  · {{ run.finished ? t('jobs.historyFinished') : t('jobs.historyUnfinished') }}
                </template>
                <template v-if="run.duration_ms !== undefined && run.duration_ms !== null">
                  · {{ t('jobs.historyDuration') }} {{ formatDuration(run.duration_ms) }}
                </template>
              </div>
              <pre v-if="run.summary || run.output" class="run-output">{{ run.summary || run.output }}</pre>
              <div v-if="run.error || run.delivery_error" class="error-line">
                {{ run.error || run.delivery_error }}
              </div>
            </div>
          </div>
        </NSpin>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.job-card {
  background-color: $bg-card;
  border: 1px solid $border-color;
  border-radius: $radius-md;
  padding: 16px;
  transition: border-color $transition-fast;

  &:hover {
    border-color: rgba(var(--accent-primary-rgb), 0.3);
  }
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.job-name {
  font-size: 15px;
  font-weight: 600;
  color: $text-primary;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 70%;
}

.status-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;

  &.success {
    background: rgba(var(--success-rgb), 0.12);
    color: $success;
  }

  &.info {
    background: rgba(var(--accent-primary-rgb), 0.12);
    color: $accent-primary;
  }

  &.warning {
    background: rgba(var(--warning-rgb), 0.12);
    color: $warning;
  }

  &.error {
    background: rgba(var(--error-rgb), 0.12);
    color: $error;
  }
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 14px;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.info-label {
  font-size: 12px;
  color: $text-muted;
}

.info-value {
  font-size: 12px;
  color: $text-secondary;
  min-width: 0;
  overflow-wrap: anywhere;
  text-align: right;
}

.schedule-value {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  min-width: 0;
  flex-wrap: wrap;
}

.schedule-kind {
  font-size: 11px;
  line-height: 1.5;
  color: $accent-primary;
  background: rgba(var(--accent-primary-rgb), 0.1);
  border: 1px solid rgba(var(--accent-primary-rgb), 0.18);
  border-radius: 6px;
  padding: 1px 6px;
}

.run-status {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 500;

  &.ok { color: $success; }
  &.err { color: $error; }
}

.mono {
  font-family: $font-code;
  font-size: 12px;
}

.job-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}

.job-badge {
  font-size: 11px;
  line-height: 1.6;
  padding: 1px 6px;
  border-radius: 6px;
  color: $text-secondary;
  background: $bg-card;
  border: 1px solid $border-light;
  max-width: 100%;
  overflow-wrap: anywhere;
}

.error-line {
  color: $error;
  font-size: 12px;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.pause-line {
  color: $warning;
  font-size: 12px;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.output-preview {
  color: $text-secondary;
  background: $bg-card;
  border: 1px solid $border-light;
  border-radius: 6px;
  padding: 8px;
  font-family: $font-code;
  font-size: 12px;
  line-height: 1.45;
  max-height: 96px;
  overflow: hidden;
  white-space: pre-wrap;
}

.card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  border-top: 1px solid $border-light;
  padding-top: 10px;
}

.detail-section {
  border-bottom: 1px solid $border-light;
  padding-bottom: 14px;
  margin-bottom: 14px;

  h4 {
    margin: 0 0 10px;
    color: $text-primary;
    font-size: 13px;
    font-weight: 600;
  }
}

.detail-grid {
  display: grid;
  grid-template-columns: 130px minmax(0, 1fr);
  gap: 7px 10px;
  align-items: start;

  span {
    color: $text-muted;
    font-size: 12px;
  }

  code {
    color: $text-secondary;
    font-family: $font-code;
    font-size: 12px;
    overflow-wrap: anywhere;
    white-space: pre-wrap;
  }
}

.empty-runs {
  color: $text-muted;
  font-size: 13px;
  padding: 18px 0;
}

.run-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.run-item {
  border: 1px solid $border-light;
  border-radius: 6px;
  padding: 12px;
  background: $bg-card;
}

.run-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 4px;
}

.run-time,
.run-meta {
  color: $text-muted;
  font-size: 12px;
}

.run-output {
  margin: 8px 0 0;
  padding: 8px;
  border-radius: 6px;
  background: rgba(var(--accent-primary-rgb), 0.06);
  color: $text-secondary;
  font-family: $font-code;
  font-size: 12px;
  line-height: 1.45;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 240px;
  overflow: auto;
}
</style>
