<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NButton, NSpin } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import JobsPanel from '@/components/jimuqu/jobs/JobsPanel.vue'
import JobFormModal from '@/components/jimuqu/jobs/JobFormModal.vue'
import { fetchCronGuide, fetchCronPolicy, type CronGuide, type CronPolicy } from '@/api/jimuqu/jobs'
import { useJobsStore } from '@/stores/jimuqu/jobs'

const { t } = useI18n()
const jobsStore = useJobsStore()
const showModal = ref(false)
const editingJob = ref<string | null>(null)
const guide = ref<CronGuide | null>(null)
const policy = ref<CronPolicy | null>(null)
const showGuide = ref(false)

onMounted(() => {
  jobsStore.fetchJobs()
  jobsStore.fetchUpcomingJobs()
  loadGuide()
  loadPolicy()
})

async function loadGuide() {
  try {
    guide.value = await fetchCronGuide()
  } catch (err) {
    console.error('Failed to fetch cron guide:', err)
  }
}

async function loadPolicy() {
  try {
    policy.value = await fetchCronPolicy()
  } catch (err) {
    console.error('Failed to fetch cron policy:', err)
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

function valueList(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(item => valueText(item)).filter(item => item && item !== '—')
  if (typeof value === 'string' && value.trim()) return [value.trim()]
  return []
}

function policyFlags(): string[] {
  const flags: string[] = []
  if (policy.value?.sourceScopedList) flags.push('会话来源隔离')
  if (policy.value?.freshSessionRuns) flags.push('独立会话运行')
  if (policy.value?.selfContainedPromptRequired) flags.push('提示词需自包含')
  if (policy.value?.recursiveCronCreationDiscouraged) flags.push('避免递归创建任务')
  return flags
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
        <div v-if="policy" class="policy-grid">
          <section class="guide-block">
            <span>动作语法</span>
            <code v-for="syntax in Object.values(policy.action_syntax || {}).slice(0, 4)" :key="syntax">
              {{ syntax }}
            </code>
          </section>
          <section class="guide-block">
            <span>可更新字段</span>
            <p>{{ valueText(policy.update_fields) }}</p>
          </section>
          <section class="guide-block">
            <span>可清空字段</span>
            <p>{{ valueText(policy.clear_fields) }}</p>
          </section>
          <section class="guide-block">
            <span>执行策略</span>
            <p>{{ valueText(policyFlags()) }}</p>
            <p>安全审批：{{ valueText(policy.execution?.dangerousCommandApprovalApplied) }} · 历史：{{ valueText(policy.execution?.historySupported) }}</p>
          </section>
          <section class="guide-block">
            <span>投递能力</span>
            <p>{{ valueText(policy.delivery?.targetForms) }}</p>
            <p>{{ valueText(policy.delivery?.wrapFlags) }}</p>
          </section>
          <section class="guide-block">
            <span>技能与依赖</span>
            <p>{{ valueText(policy.skill_binding?.appendFlags) }}</p>
            <p>{{ valueText(policy.skill_binding?.dependencyFlags) }}</p>
          </section>
          <section class="guide-block">
            <span>调度能力</span>
            <p>{{ valueText(valueList(policy.schedule?.cronExpressionSupported).length ? valueList(policy.schedule?.cronExpressionSupported) : ['cron', 'interval', 'once']) }}</p>
          </section>
          <section class="guide-block">
            <span>历史字段</span>
            <p>{{ valueText(policy.history_fields) }}</p>
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

.policy-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-top: 10px;
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
    overflow-wrap: anywhere;
  }

  code {
    display: block;
    margin-top: 6px;
    color: $text-secondary;
    font-family: $font-code;
    font-size: 12px;
    line-height: 1.5;
    overflow-wrap: anywhere;
    white-space: pre-wrap;
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
  .guide-grid,
  .policy-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .guide-meta {
    justify-content: flex-start;
    max-width: none;
  }
}
</style>
