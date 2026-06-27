<script setup lang="ts">
import JobCard from './JobCard.vue'
import { useJobsStore } from '@/stores/solonclaw/jobs'
import { useI18n } from 'vue-i18n'
import { formatJobTime, jobScheduleLabel } from '@/shared/jobsDisplay'

const { t } = useI18n()

const emit = defineEmits<{
  edit: [jobId: string]
  changed: []
}>()

const jobsStore = useJobsStore()

function refreshUpcoming() {
  jobsStore.fetchUpcomingJobs()
}
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
          <span class="upcoming-time">{{ formatJobTime(job.next_run_at) }}</span>
          <span class="upcoming-name">{{ job.name }}</span>
          <code class="upcoming-schedule">{{ jobScheduleLabel(job) }}</code>
        </button>
      </div>
    </section>

    <section class="list-panel">
      <div class="list-head">
        <div>
          <span class="list-title">{{ t('jobs.myJobsTitle') }}</span>
          <p>{{ t('jobs.myJobsDesc') }}</p>
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

.list-panel {
  border: 1px solid $border-light;
  border-radius: $radius-md;
  background: $bg-card;
  padding: 12px;
}

.list-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;

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

.list-title {
  font-size: 13px;
  font-weight: 600;
  color: $text-primary;
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
  .upcoming-item {
    grid-template-columns: minmax(0, 1fr);
  }

  .upcoming-schedule {
    text-align: left;
  }
}
</style>
