<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Button, Spin } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import JobsPanel from '@/components/solonclaw/jobs/JobsPanel.vue'
import JobFormModal from '@/components/solonclaw/jobs/JobFormModal.vue'
import { useJobsStore } from '@/stores/solonclaw/jobs'

const { t } = useI18n()
const jobsStore = useJobsStore()
const showModal = ref(false)
const editingJob = ref<string | null>(null)

onMounted(() => {
  jobsStore.fetchJobs()
  jobsStore.fetchUpcomingJobs()
  jobsStore.fetchStatus()
})

function openCreateModal() {
  editingJob.value = null
  showModal.value = true
}

function openEditModal(jobId: string) {
  editingJob.value = jobId
  showModal.value = true
}

function handleModalClose() {
  showModal.value = false
  editingJob.value = null
}

async function handleSave() {
  await jobsStore.fetchJobs()
  await jobsStore.fetchUpcomingJobs()
  await jobsStore.fetchStatus()
  handleModalClose()
}

async function refreshSchedules() {
  await Promise.all([
    jobsStore.fetchJobs(),
    jobsStore.fetchUpcomingJobs(),
    jobsStore.fetchStatus(),
  ])
}
</script>

<template>
  <div class="jobs-view">
    <header class="page-header">
      <h2 class="header-title">{{ t('jobs.title') }}</h2>
      <Button type="primary" size="small" @click="openCreateModal">
        <template #icon>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        </template>
        {{ t('jobs.createJob') }}
      </Button>
    </header>

    <section v-if="jobsStore.status" class="status-panel">
      <div class="status-grid">
        <div class="status-item">
          <span>{{ t('jobs.pageTotal') }}</span>
          <strong>{{ jobsStore.status.total }}</strong>
        </div>
        <div class="status-item">
          <span>{{ t('jobs.pageActiveFriendly') }}</span>
          <strong>{{ jobsStore.status.active }}</strong>
        </div>
        <div class="status-item">
          <span>{{ t('jobs.pagePaused') }}</span>
          <strong>{{ jobsStore.status.paused }}</strong>
        </div>
        <div class="status-item">
          <span>{{ t('jobs.pageCompleted') }}</span>
          <strong>{{ jobsStore.status.completed }}</strong>
        </div>
        <div class="status-item due" :class="{ hot: jobsStore.status.due > 0 }">
          <span>{{ t('jobs.pageDueFriendly') }}</span>
          <strong>{{ jobsStore.status.due }}</strong>
        </div>
      </div>
      <div class="status-side">
        <div class="status-list">
          <span>{{ t('jobs.pageRecentFailures') }}</span>
          <template v-if="jobsStore.status.recent_failures.length">
            <code v-for="failure in jobsStore.status.recent_failures.slice(0, 3)" :key="failure.job_id || failure.id || failure.name">
              {{ failure.name || failure.job_id || failure.id }} · {{ failure.last_error || failure.last_delivery_error || failure.last_status }}
            </code>
          </template>
          <code v-else>—</code>
        </div>
      </div>
    </section>

    <div class="jobs-content">
      <Spin :spinning="jobsStore.loading && jobsStore.jobs.length === 0">
        <JobsPanel @edit="openEditModal" @changed="refreshSchedules" />
      </Spin>
    </div>

    <JobFormModal
      v-if="showModal"
      :job-id="editingJob"
      @close="handleModalClose"
      @saved="handleSave"
    />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.jobs-view {
  height: calc(100 * var(--vh));
  display: flex;
  flex-direction: column;
}

.jobs-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.status-panel {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
  gap: 12px;
  margin: 12px 20px 0;
  border: 1px solid $border-light;
  border-radius: $radius-md;
  background: $bg-card;
  padding: 12px 14px;
  flex-shrink: 0;
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(92px, 1fr));
  gap: 8px;
}

.status-item {
  min-width: 0;
  border: 1px solid $border-light;
  border-radius: 6px;
  background: $bg-card-hover;
  padding: 8px 10px;

  span {
    display: block;
    color: $text-muted;
    font-size: 12px;
    line-height: 1.4;
  }

  strong {
    display: block;
    margin-top: 3px;
    color: $text-primary;
    font-size: 18px;
    line-height: 1.3;
  }

  &.hot strong {
    color: $warning;
  }
}

.status-side {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 10px;
}

.status-list {
  min-width: 0;

  span {
    display: block;
    margin-bottom: 5px;
    color: $text-muted;
    font-size: 12px;
  }

  code {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: $text-secondary;
    font-family: $font-code;
    font-size: 12px;
    line-height: 1.6;
  }
}

@media (max-width: $breakpoint-mobile) {
  .status-panel {
    grid-template-columns: minmax(0, 1fr);
  }

  .status-grid,
  .status-side {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .status-panel {
    grid-template-columns: 1fr;
  }
}

@media (max-width: $breakpoint-mobile) {
  .jobs-content {
    padding: 12px;
  }

  .status-panel {
    margin-left: 12px;
    margin-right: 12px;
  }

  .status-grid,
  .status-side {
    grid-template-columns: 1fr;
  }
}
</style>
