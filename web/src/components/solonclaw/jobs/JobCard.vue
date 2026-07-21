<script setup lang="ts">
import { computed, ref } from 'vue'
import { Button, Drawer, Modal, Spin, TextArea, Tooltip, message } from 'antdv-next'
import type { Job, JobRun } from '@/api/solonclaw/jobs'
import { useJobsStore } from '@/stores/solonclaw/jobs'
import { useI18n } from 'vue-i18n'
import {
  formatJobTime,
  humanizeJobToken,
  inferJobScheduleKind,
  jobActionSummary,
  jobAliasSummary,
  jobBadges,
  jobDeliveryTargetLabel,
  jobListDetail,
  jobScheduleLabel,
  jobStatusLabel,
  jobStatusTone,
  joinJobDetailParts,
} from '@/shared/jobsDisplay'

const props = defineProps<{ job: Job }>()
const emit = defineEmits<{
  edit: [jobId: string]
  changed: []
}>()

const { t } = useI18n()
const jobsStore = useJobsStore()
const showRuns = ref(false)
const showPauseModal = ref(false)
const pauseReason = ref('')
const runsLoading = ref(false)
const runs = ref<JobRun[]>([])
const detailJob = ref<Job | null>(null)

const jobId = computed(() => props.job.job_id || props.job.id)
const activeJob = computed(() => detailJob.value || props.job)
const actionFlags = computed(() => activeJob.value.actions || props.job.actions || {})

const statusLabel = computed(() => jobStatusLabel(t, props.job))
const statusType = computed(() => jobStatusTone(props.job))

const canPause = computed(() => actionFlags.value.can_pause ?? (props.job.state !== 'paused' && props.job.enabled))
const canResume = computed(() => actionFlags.value.can_resume ?? props.job.state === 'paused')
const canRun = computed(() => actionFlags.value.can_run !== false)
const canRetry = computed(() => actionFlags.value.can_retry === true)
const canInspect = computed(() => actionFlags.value.can_inspect !== false)
const canEdit = computed(() => actionFlags.value.can_edit !== false)
const canRemove = computed(() => actionFlags.value.can_remove !== false)

const actionSummary = computed(() => jobActionSummary(t, actionFlags.value))
const aliasSummary = computed(() => jobAliasSummary(t, actionFlags.value))

const scheduleExpr = computed(() => jobScheduleLabel(props.job))
const scheduleKind = computed(() => inferJobScheduleKind(props.job))

const scheduleKindLabel = computed(() => {
  if (scheduleKind.value === 'interval') return t('jobs.scheduleKindInterval')
  if (scheduleKind.value === 'once') return t('jobs.scheduleKindOnce')
  return t('jobs.scheduleKindCron')
})

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

const badges = computed(() => jobBadges(t, props.job))

const deliverDetail = computed(() => {
  const job = activeJob.value
  const parts = [humanizeJobToken(t, job.deliver || 'local', { fallback: '—' })]
  if (job.origin?.platform) parts.push(humanizeJobToken(t, job.origin.platform, { fallback: '—' }))
  if (job.deliver_chat_id) parts.push(job.deliver_chat_id)
  if (job.deliver_thread_id) parts.push(`#${job.deliver_thread_id}`)
  return joinJobDetailParts(parts)
})

const modelDetail = computed(() => {
  const parts = [activeJob.value.provider, activeJob.value.model].filter(Boolean)
  return parts.length ? parts.join(' · ') : '—'
})

function listDetail(values?: string[] | null) {
  return jobListDetail(values)
}

function boolDetail(value: boolean) {
  return value ? t('jobs.detail.yes') : t('jobs.detail.no')
}

function tokenLabel(value?: string | null) {
  return humanizeJobToken(t, value, { fallback: '—' })
}

async function handlePause() {
  try {
    await jobsStore.pauseJob(jobId.value, pauseReason.value)
    showPauseModal.value = false
    pauseReason.value = ''
    emit('changed')
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
    emit('changed')
    message.success(t('jobs.jobResumed'))
  } catch (e: any) {
    message.error(e.message)
  }
}

async function handleRun() {
  try {
    if (canRetry.value) {
      await jobsStore.retryJob(jobId.value)
    } else {
      await jobsStore.runJob(jobId.value)
    }
    if (showRuns.value) {
      await refreshRuns()
    }
    emit('changed')
    message.info(canRetry.value ? t('jobs.jobRetried') : t('jobs.jobTriggered'))
  } catch (e: any) {
    message.error(e.message)
  }
}

async function refreshRuns() {
  runsLoading.value = true
  try {
    const inspect = await jobsStore.inspectJob(jobId.value, 20)
    detailJob.value = inspect.job
    runs.value = inspect.runs
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
    emit('changed')
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
        <span class="info-label">{{ t('jobs.info.nextRun') }}</span>
        <span class="info-value primary-time">{{ formatJobTime(job.next_run_at) }}</span>
      </div>
      <div class="info-row">
        <span class="info-label">{{ t('jobs.info.lastRun') }}</span>
        <span class="info-value">
          {{ formatJobTime(job.last_run_at) }}
          <span v-if="job.last_status" class="run-status" :class="{ ok: job.last_status === 'ok', err: job.last_status !== 'ok' }">
            {{ tokenLabel(job.last_status === 'ok' ? 'ok' : job.last_status) }}
          </span>
        </span>
      </div>
      <div class="info-row">
        <span class="info-label">{{ t('jobs.info.deliver') }}</span>
        <span class="info-value">{{ deliverDetail }}</span>
      </div>
      <div class="schedule-line">
        <span class="schedule-kind">{{ scheduleKindLabel }}</span>
        <code class="mono">{{ scheduleExpr }}</code>
      </div>
      <div v-if="job.repeat" class="info-row">
        <span class="info-label">{{ t('jobs.info.repeat') }}</span>
        <span class="info-value">
          <template v-if="typeof job.repeat === 'string'">{{ job.repeat }}</template>
          <template v-else>{{ job.repeat.completed }} / {{ job.repeat.times ?? '∞' }}</template>
        </span>
      </div>
      <div v-if="badges.length" class="job-badges">
        <span v-for="badge in badges" :key="badge" class="job-badge">{{ badge }}</span>
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
      <Tooltip v-if="canPause" :title="t('jobs.action.pauseJob')">
        <Button size="small" type="text" @click="openPauseModal">{{ t('jobs.action.pause') }}</Button>
      </Tooltip>
      <Tooltip v-else-if="canResume" :title="t('jobs.action.resumeJob')">
        <Button size="small" type="text" @click="handleResume">{{ t('jobs.action.resume') }}</Button>
      </Tooltip>
      <Tooltip v-if="canRun" :title="canRetry ? t('jobs.action.rerunJob') : t('jobs.action.triggerImmediately')">
        <Button size="small" type="text" @click="handleRun">
          {{ canRetry ? t('jobs.action.retry') : t('jobs.action.runNow') }}
        </Button>
      </Tooltip>
      <Button v-if="canInspect" size="small" type="text" @click="openRuns">{{ t('jobs.action.detail') }}</Button>
      <Button v-if="canEdit" size="small" type="text" @click="emit('edit', jobId)">{{ t('common.edit') }}</Button>
      <Button v-if="canRemove" size="small" type="text" danger @click="handleDelete">{{ t('common.delete') }}</Button>
    </div>

    <Modal
      v-model:open="showPauseModal"

      :title="t('jobs.pauseTitle')"
      :ok-text="t('jobs.action.pause')"
      :cancel-text="t('common.cancel')"
      @ok="handlePause"
    >
      <TextArea
        v-model:value="pauseReason"
        :rows="3"
        :maxlength="300"
        show-count
        :placeholder="t('jobs.pauseReasonPlaceholder')"
      />
    </Modal>

    <Drawer v-model:open="showRuns" placement="right" :style="{ width: '520px' }" :title="t('jobs.historyTitle', { name: job.name })">
        <section class="detail-section">
          <h4>{{ t('jobs.detail.config') }}</h4>
          <div class="detail-grid">
            <span>{{ t('jobs.detail.skills') }}</span>
            <code>{{ listDetail(activeJob.skills) }}</code>
            <span>{{ t('jobs.detail.deliver') }}</span>
            <code>{{ deliverDetail }}</code>
            <span>{{ t('jobs.detail.wrapResponse') }}</span>
            <code>{{ boolDetail(activeJob.wrap_response) }}</code>
            <span>{{ t('jobs.detail.script') }}</span>
            <code>{{ activeJob.script || '—' }}</code>
            <span>{{ t('jobs.detail.noAgent') }}</span>
            <code>{{ boolDetail(activeJob.no_agent) }}</code>
            <span>{{ t('jobs.detail.workdir') }}</span>
            <code>{{ activeJob.workdir || '—' }}</code>
            <span>{{ t('jobs.detail.contextFrom') }}</span>
            <code>{{ listDetail(activeJob.context_from) }}</code>
            <span>{{ t('jobs.detail.enabledToolsets') }}</span>
            <code>{{ listDetail(activeJob.enabled_toolsets) }}</code>
            <span>{{ t('jobs.detail.model') }}</span>
            <code>{{ modelDetail }}</code>
            <span>{{ t('jobs.detail.pendingTrigger') }}</span>
            <code>{{ activeJob.pending_trigger || '—' }}</code>
            <span>{{ t('jobs.detail.actions') }}</span>
            <code>{{ actionSummary }}</code>
            <span>{{ t('jobs.detail.aliases') }}</span>
            <code>{{ aliasSummary }}</code>
          </div>
        </section>
        <section
          v-if="activeJob.last_output || activeJob.last_error || activeJob.last_delivery_error"
          class="detail-section"
        >
          <h4>{{ t('jobs.detail.lastResult') }}</h4>
          <div v-if="activeJob.last_error || activeJob.last_delivery_error" class="error-line detail-error">
            {{ activeJob.last_error || activeJob.last_delivery_error }}
          </div>
          <pre v-if="activeJob.last_output" class="run-output full-output">{{ activeJob.last_output }}</pre>
        </section>
        <Spin :spinning="runsLoading">
          <div v-if="runs.length === 0" class="empty-runs">{{ t('jobs.noHistory') }}</div>
          <div v-else class="run-list">
            <div v-for="run in runs" :key="run.run_id" class="run-item">
              <div class="run-head">
                <span class="run-status" :class="{ ok: run.status === 'ok', err: run.status && run.status !== 'ok' }">
                  {{ tokenLabel(run.status) }}
                </span>
                <span class="run-time">{{ formatJobTime(run.started_at) }}</span>
              </div>
              <div class="run-meta">
                {{ t('jobs.historyTrigger') }} {{ tokenLabel(run.trigger || 'scheduled') }}
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
              <div v-if="run.delivery_result" class="delivery-result">
                <div class="delivery-summary">
                  {{ t('jobs.historyDelivery') }}
                  <template v-if="run.delivery_result.skipped">
                    · {{ t('jobs.historyDeliverySkipped') }} {{ run.delivery_result.skipped }}
                  </template>
                  <template v-else>
                    · {{ t('jobs.historyDeliveryOk') }} {{ run.delivery_result.delivered || 0 }}
                    · {{ t('jobs.historyDeliveryFailed') }} {{ run.delivery_result.failed || 0 }}
                  </template>
                </div>
                <div v-if="run.delivery_result.targets?.length" class="delivery-targets">
                  <div
                    v-for="(target, index) in run.delivery_result.targets"
                    :key="`${run.run_id}:${index}`"
                    class="delivery-target"
                    :class="{ err: target.status === 'error' }"
                  >
                    <span>{{ jobDeliveryTargetLabel(t, target) }}</span>
                    <span>{{ tokenLabel(target.status) }}</span>
                    <span v-if="target.attachments">{{ t('jobs.historyDeliveryAttachments') }} {{ target.attachments }}</span>
                    <span v-if="target.error" class="delivery-target-error">{{ target.error }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </Spin>
    </Drawer>
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

.schedule-kind {
  font-size: 11px;
  line-height: 1.5;
  color: $accent-primary;
  background: rgba(var(--accent-primary-rgb), 0.1);
  border: 1px solid rgba(var(--accent-primary-rgb), 0.18);
  border-radius: 6px;
  padding: 1px 6px;
}

.schedule-line {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  margin-top: 2px;
  color: $text-muted;

  code {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.primary-time {
  color: $text-primary;
  font-weight: 600;
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

.delivery-result {
  margin-top: 8px;
  padding: 8px;
  border: 1px solid $border-light;
  border-radius: 6px;
  background: rgba(var(--accent-primary-rgb), 0.04);
  font-size: 12px;
}

.delivery-summary {
  color: $text-secondary;
  margin-bottom: 6px;
}

.delivery-targets {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.delivery-target {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 8px;
  align-items: center;
  color: $text-muted;

  &.err {
    color: $error;
  }
}

.delivery-target-error {
  grid-column: 1 / -1;
  overflow-wrap: anywhere;
}

.detail-error {
  margin-bottom: 8px;
}

.full-output {
  max-height: 360px;
}
</style>
