<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NTooltip, useMessage } from 'naive-ui'
import type { Job } from '@/api/hermes/jobs'
import { useJobsStore } from '@/stores/hermes/jobs'
import { useI18n } from 'vue-i18n'

const props = defineProps<{ job: Job }>()
const emit = defineEmits<{
  edit: [jobId: string]
}>()

const { t } = useI18n()
const jobsStore = useJobsStore()
const message = useMessage()

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

const formatTime = (t?: string | null) => {
  if (!t) return '—'
  return new Date(t).toLocaleString()
}

const jobBadges = computed(() => {
  const badges: string[] = []
  if (props.job.no_agent) badges.push(t('jobs.badge.noAgent'))
  if (props.job.script) badges.push(t('jobs.badge.script'))
  if (props.job.skills?.length) badges.push(t('jobs.badge.skills', { count: props.job.skills.length }))
  if (props.job.context_from?.length) badges.push(t('jobs.badge.context', { count: props.job.context_from.length }))
  if (props.job.enabled_toolsets?.length) badges.push(t('jobs.badge.toolsets', { count: props.job.enabled_toolsets.length }))
  if (props.job.model) badges.push(props.job.provider ? `${props.job.provider}:${props.job.model}` : props.job.model)
  return badges
})

async function handlePause() {
  try {
    await jobsStore.pauseJob(jobId.value)
    message.success(t('jobs.jobPaused'))
  } catch (e: any) {
    message.error(e.message)
  }
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
    message.info(t('jobs.jobTriggered'))
  } catch (e: any) {
    message.error(e.message)
  }
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
        <code class="info-value mono">{{ scheduleExpr }}</code>
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
        <span class="info-value">{{ job.deliver }}<template v-if="job.origin"> ({{ job.origin.platform }})</template></span>
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
    </div>

    <div class="card-actions">
      <NTooltip v-if="job.state !== 'paused' && job.enabled">
        <template #trigger>
          <NButton size="tiny" quaternary @click="handlePause">{{ t('jobs.action.pause') }}</NButton>
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
      <NButton size="tiny" quaternary @click="emit('edit', jobId)">{{ t('common.edit') }}</NButton>
      <NButton size="tiny" quaternary type="error" @click="handleDelete">{{ t('common.delete') }}</NButton>
    </div>
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

.card-actions {
  display: flex;
  gap: 4px;
  border-top: 1px solid $border-light;
  padding-top: 10px;
}
</style>
