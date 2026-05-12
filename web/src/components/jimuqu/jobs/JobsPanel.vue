<script setup lang="ts">
import JobCard from './JobCard.vue'
import { useJobsStore } from '@/stores/jimuqu/jobs'
import { useI18n } from 'vue-i18n'
import { computed, onMounted } from 'vue'

const { t } = useI18n()

const emit = defineEmits<{
  edit: [jobId: string]
  changed: []
}>()

const jobsStore = useJobsStore()

const formatTime = (value?: string | null) => {
  if (!value) return '—'
  return new Date(value).toLocaleString()
}

const scheduleLabel = (job: any) => {
  const schedule = job.schedule
  if (typeof schedule === 'string') return schedule
  return schedule?.display || schedule?.expr || job.schedule_display || '—'
}

function refreshUpcoming() {
  jobsStore.fetchUpcomingJobs()
}

const guideActions = computed(() => {
  const actions = jobsStore.guide?.actions || {}
  return Object.keys(actions).map(key => `${key}: ${actions[key]}`)
})

const guideDeliveries = computed(() => {
  const delivery = jobsStore.guide?.delivery || {}
  const modes = Array.isArray(delivery.modes) ? delivery.modes : []
  const targets = Array.isArray(delivery.targets) ? delivery.targets : []
  return [
    ...modes.map(String),
    targets.length ? `${t('jobs.guideTargets')}: ${targets.join(', ')}` : '',
  ].filter(Boolean)
})

const guideAutomation = computed(() => {
  const guide = jobsStore.guide
  const policy = jobsStore.policy
  return [
    guide?.skill_binding?.multipleSkillsSupported ? t('jobs.guideMultiSkill') : '',
    guide?.skill_binding?.skillRewriteSupported ? t('jobs.guideSkillRewrite') : '',
    policy?.schedule?.intervalSupported ? t('jobs.guideInterval') : '',
    policy?.schedule?.onceSupported ? t('jobs.guideOnce') : '',
    policy?.execution?.noAgentScriptSupported ? t('jobs.guideNoAgent') : '',
    policy?.delivery?.multiTargetDeliverySupported ? t('jobs.guideMultiTarget') : '',
    policy?.delivery?.wrapResponseSupported ? t('jobs.guideWrap') : '',
  ].filter(Boolean)
})

onMounted(() => {
  if (!jobsStore.guide && !jobsStore.guideLoading) {
    jobsStore.fetchGuideAndPolicy()
  }
})
</script>

<template>
  <div class="jobs-panel">
    <section v-if="jobsStore.upcomingJobs.length || jobsStore.upcomingLoading" class="upcoming-panel">
      <div class="upcoming-head">
        <span class="upcoming-title">{{ t('jobs.upcomingTitle') }}</span>
        <button class="upcoming-refresh" type="button" @click="refreshUpcoming">
          {{ jobsStore.upcomingLoading ? t('common.loading') : t('jobs.action.refreshUpcoming') }}
        </button>
      </div>
      <div class="upcoming-meta">{{ t('jobs.upcomingCount', { count: jobsStore.upcomingJobs.length }) }}</div>
      <div class="upcoming-list">
        <button
          v-for="job in jobsStore.upcomingJobs"
          :key="job.id"
          class="upcoming-item"
          type="button"
          @click="emit('edit', job.id)"
        >
          <span class="upcoming-time">{{ formatTime(job.next_run_at) }}</span>
          <span class="upcoming-name">{{ job.name }}</span>
          <code class="upcoming-schedule">{{ scheduleLabel(job) }}</code>
        </button>
      </div>
    </section>

    <section class="guide-panel">
      <div class="guide-head">
        <div>
          <span class="guide-title">{{ t('jobs.guideTitle') }}</span>
          <p>{{ t('jobs.guideDescription') }}</p>
        </div>
        <button class="upcoming-refresh" type="button" @click="jobsStore.fetchGuideAndPolicy">
          {{ jobsStore.guideLoading ? t('common.loading') : t('common.refresh') }}
        </button>
      </div>
      <div class="guide-grid">
        <div class="guide-block">
          <span class="guide-block-title">{{ t('jobs.guideAutomation') }}</span>
          <span v-for="item in guideAutomation" :key="item" class="guide-chip">{{ item }}</span>
          <span v-if="!guideAutomation.length" class="guide-muted">{{ t('common.noData') }}</span>
        </div>
        <div class="guide-block">
          <span class="guide-block-title">{{ t('jobs.guideDelivery') }}</span>
          <span v-for="item in guideDeliveries" :key="item" class="guide-chip">{{ item }}</span>
          <span v-if="!guideDeliveries.length" class="guide-muted">{{ t('common.noData') }}</span>
        </div>
        <div class="guide-block">
          <span class="guide-block-title">{{ t('jobs.guideActions') }}</span>
          <span v-for="item in guideActions" :key="item" class="guide-chip">{{ item }}</span>
          <span v-if="!guideActions.length" class="guide-muted">{{ t('common.noData') }}</span>
        </div>
      </div>
    </section>

    <div v-if="jobsStore.jobs.length === 0" class="empty-state">
      <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" class="empty-icon">
        <rect x="3" y="4" width="18" height="18" rx="2" ry="2"/>
        <line x1="16" y1="2" x2="16" y2="6"/>
        <line x1="8" y1="2" x2="8" y2="6"/>
        <line x1="3" y1="10" x2="21" y2="10"/>
      </svg>
      <p>{{ t('jobs.noJobs') }}</p>
    </div>
    <div v-else class="jobs-grid">
      <JobCard
        v-for="job in jobsStore.jobs"
        :key="job.id"
        :job="job"
        @edit="emit('edit', job.id)"
        @changed="emit('changed')"
      />
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.jobs-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.upcoming-panel {
  border: 1px solid $border-light;
  border-radius: $radius-md;
  background: $bg-card;
  padding: 12px;
}

.guide-panel {
  border: 1px solid $border-light;
  border-radius: $radius-md;
  background: $bg-card;
  padding: 12px;
}

.guide-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;

  p {
    margin: 4px 0 0;
    color: $text-muted;
    font-size: 12px;
    line-height: 1.5;
  }
}

.upcoming-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.upcoming-title {
  font-size: 13px;
  font-weight: 600;
  color: $text-primary;
}

.guide-title {
  font-size: 13px;
  font-weight: 600;
  color: $text-primary;
}

.guide-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.guide-block {
  min-width: 0;
  border: 1px solid $border-light;
  border-radius: 6px;
  padding: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-content: flex-start;
}

.guide-block-title {
  flex-basis: 100%;
  color: $text-secondary;
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 2px;
}

.guide-chip {
  max-width: 100%;
  border: 1px solid $border-light;
  border-radius: 6px;
  color: $text-secondary;
  font-size: 12px;
  line-height: 1.4;
  padding: 2px 6px;
  overflow-wrap: anywhere;
}

.guide-muted {
  color: $text-muted;
  font-size: 12px;
}

.upcoming-meta {
  font-size: 12px;
  color: $text-muted;
  margin-bottom: 8px;
}

.upcoming-refresh {
  border: 1px solid $border-light;
  border-radius: 6px;
  background: transparent;
  color: $text-secondary;
  cursor: pointer;
  font-size: 12px;
  line-height: 1.5;
  padding: 2px 8px;
  transition: background $transition-fast, border-color $transition-fast;

  &:hover {
    background: $bg-card-hover;
    border-color: $border-color;
  }
}

.upcoming-list {
  display: grid;
  gap: 6px;
}

.upcoming-item {
  display: grid;
  grid-template-columns: minmax(120px, 180px) minmax(120px, 1fr) minmax(80px, 180px);
  gap: 10px;
  align-items: center;
  width: 100%;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: $text-secondary;
  cursor: pointer;
  padding: 7px 8px;
  text-align: left;
  transition: background $transition-fast, border-color $transition-fast;

  &:hover {
    background: $bg-card-hover;
    border-color: $border-light;
  }
}

.upcoming-time,
.upcoming-name,
.upcoming-schedule {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.upcoming-time {
  font-size: 12px;
  color: $text-muted;
}

.upcoming-name {
  font-size: 13px;
  color: $text-primary;
  font-weight: 500;
}

.upcoming-schedule {
  font-family: $font-code;
  font-size: 12px;
  color: $text-muted;
  text-align: right;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: $text-muted;
  gap: 12px;

  .empty-icon {
    opacity: 0.3;
  }

  p {
    font-size: 14px;
  }
}

.jobs-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 360px), 1fr));
  gap: 14px;
}

@media (max-width: $breakpoint-mobile) {
  .guide-grid {
    grid-template-columns: 1fr;
  }

  .upcoming-item {
    grid-template-columns: minmax(0, 1fr);
  }

  .upcoming-schedule {
    text-align: left;
  }
}
</style>
