<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NButton, NSpin } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import JobsPanel from '@/components/jimuqu/jobs/JobsPanel.vue'
import JobFormModal from '@/components/jimuqu/jobs/JobFormModal.vue'
import { fetchCronGuide, type CronGuide } from '@/api/jimuqu/jobs'
import { useJobsStore } from '@/stores/jimuqu/jobs'

const { t } = useI18n()
const jobsStore = useJobsStore()
const showModal = ref(false)
const editingJob = ref<string | null>(null)
const guide = ref<CronGuide | null>(null)
const showGuide = ref(false)

onMounted(() => {
  jobsStore.fetchJobs()
  jobsStore.fetchUpcomingJobs()
  loadGuide()
})

async function loadGuide() {
  try {
    guide.value = await fetchCronGuide()
  } catch (err) {
    console.error('Failed to fetch cron guide:', err)
  }
}

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
  handleModalClose()
}

async function refreshSchedules() {
  await jobsStore.fetchUpcomingJobs()
}

function listText(value?: string[]): string {
  return value?.filter(Boolean).join('、') || '—'
}

function mapKeys(value?: Record<string, unknown>): string {
  return value ? Object.keys(value).join('、') : '—'
}

function valueText(value: unknown): string {
  if (Array.isArray(value)) return value.join('、')
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (value === null || value === undefined || value === '') return '—'
  return String(value)
}
</script>

<template>
  <div class="jobs-view">
    <header class="page-header">
      <h2 class="header-title">{{ t('jobs.title') }}</h2>
      <NButton type="primary" size="small" @click="openCreateModal">
        <template #icon>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        </template>
        {{ t('jobs.createJob') }}
      </NButton>
    </header>

    <section v-if="guide" class="guide-panel" :class="{ expanded: showGuide }">
      <div class="guide-summary">
        <div>
          <div class="guide-title">Cron 自动化指南</div>
          <div class="guide-objective">{{ guide.objective }}</div>
        </div>
        <div class="guide-meta">
          <span>{{ listText(guide.schedule_types) }}</span>
          <span>{{ guide.editable_fields.length }} 个字段</span>
          <span>{{ mapKeys(guide.actions) }}</span>
        </div>
        <NButton size="small" quaternary @click="showGuide = !showGuide">
          {{ showGuide ? '收起指南' : '展开指南' }}
        </NButton>
      </div>
      <div v-if="showGuide" class="guide-body">
        <div class="guide-grid">
          <section class="guide-block">
            <span>投递策略</span>
            <strong>{{ valueText(guide.delivery.targets) }}</strong>
            <p>{{ valueText(guide.delivery.multi_target) }}</p>
          </section>
          <section class="guide-block">
            <span>技能绑定</span>
            <strong>{{ valueText(guide.skill_binding.replace) }}</strong>
            <p>追加：{{ valueText(guide.skill_binding.append) }} · 移除：{{ valueText(guide.skill_binding.remove) }}</p>
          </section>
          <section class="guide-block">
            <span>运行模式</span>
            <strong>Agent / no-agent</strong>
            <p>{{ valueText(guide.runtime_modes.no_agent) }}</p>
          </section>
          <section class="guide-block">
            <span>安全策略</span>
            <strong>{{ valueText(guide.security.prompt_scan) }}</strong>
            <p>{{ valueText(guide.security.script_validation) }}</p>
          </section>
        </div>
        <div class="guide-examples">
          <code v-for="example in guide.slash_examples.slice(0, 4)" :key="example">{{ example }}</code>
        </div>
      </div>
    </section>

    <div class="jobs-content">
      <NSpin :show="jobsStore.loading && jobsStore.jobs.length === 0">
        <JobsPanel @edit="openEditModal" @changed="refreshSchedules" />
      </NSpin>
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

.guide-panel {
  margin: 0 20px 0;
  border: 1px solid $border-light;
  border-radius: $radius-md;
  background: $bg-card;
  flex-shrink: 0;
}

.guide-summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 14px;
  align-items: center;
  padding: 12px 14px;
}

.guide-title {
  color: $text-primary;
  font-size: 14px;
  font-weight: 700;
}

.guide-objective {
  margin-top: 3px;
  color: $text-secondary;
  font-size: 12px;
  line-height: 1.5;
}

.guide-meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
  max-width: 520px;

  span {
    max-width: 180px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    border: 1px solid $border-light;
    border-radius: 6px;
    color: $text-secondary;
    font-size: 12px;
    line-height: 1.5;
    padding: 2px 8px;
  }
}

.guide-body {
  border-top: 1px solid $border-light;
  padding: 12px 14px 14px;
}

.guide-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.guide-block {
  min-width: 0;
  border: 1px solid $border-light;
  border-radius: 6px;
  background: $bg-card-hover;
  padding: 10px;

  span {
    display: block;
    color: $text-muted;
    font-size: 12px;
    margin-bottom: 5px;
  }

  strong {
    display: block;
    color: $text-primary;
    font-size: 12px;
    line-height: 1.5;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  p {
    margin: 6px 0 0;
    color: $text-secondary;
    font-size: 12px;
    line-height: 1.5;
  }
}

.guide-examples {
  display: grid;
  gap: 6px;
  margin-top: 10px;

  code {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    border: 1px solid $border-light;
    border-radius: 6px;
    color: $text-secondary;
    font-family: $font-code;
    font-size: 12px;
    padding: 7px 8px;
  }
}

@media (max-width: $breakpoint-mobile) {
  .guide-summary,
  .guide-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .guide-meta {
    justify-content: flex-start;
    max-width: none;
  }
}
</style>
