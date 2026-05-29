<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { NButton, NSpin } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import JobsPanel from '@/components/jimuqu/jobs/JobsPanel.vue'
import JobFormModal from '@/components/jimuqu/jobs/JobFormModal.vue'
import { useJobsStore } from '@/stores/jimuqu/jobs'

const { t } = useI18n()
const jobsStore = useJobsStore()
const showModal = ref(false)
const editingJob = ref<string | null>(null)
const showGuide = ref(false)
const guide = computed(() => jobsStore.guide)
const policy = computed(() => jobsStore.policy)

onMounted(() => {
  jobsStore.fetchJobs()
  jobsStore.fetchUpcomingJobs()
  jobsStore.fetchStatus()
  jobsStore.fetchGuideAndPolicy()
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
    jobsStore.fetchUpcomingJobs(),
    jobsStore.fetchStatus(),
  ])
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
  if (policy.value?.runtime_isolation?.sourceBoundSessionRuns) flags.push('来源绑定运行')
  if (policy.value?.runtime_isolation?.autoDeliveryContext) flags.push('自动投递上下文')
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
            <span>运行隔离</span>
            <strong>{{ valueText(guide.runtime_modes.session_binding) }}</strong>
            <p>禁用工具集：{{ valueText(guide.runtime_modes.disabled_toolsets) }}</p>
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
            <span>运行隔离</span>
            <p>禁用工具集：{{ valueText(policy.runtime_isolation?.disabledToolsets) }}</p>
            <p>超时：{{ valueText(policy.runtime_isolation?.inactivityTimeoutSeconds) }} 秒 · 本地投递仅入历史：{{ valueText(policy.runtime_isolation?.localDeliveryHistoryOnly) }}</p>
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

    <section v-if="jobsStore.status" class="status-panel">
      <div class="status-grid">
        <div class="status-item">
          <span>总任务</span>
          <strong>{{ jobsStore.status.total }}</strong>
        </div>
        <div class="status-item">
          <span>活跃</span>
          <strong>{{ jobsStore.status.active }}</strong>
        </div>
        <div class="status-item">
          <span>暂停</span>
          <strong>{{ jobsStore.status.paused }}</strong>
        </div>
        <div class="status-item">
          <span>完成</span>
          <strong>{{ jobsStore.status.completed }}</strong>
        </div>
        <div class="status-item due" :class="{ hot: jobsStore.status.due > 0 }">
          <span>到期</span>
          <strong>{{ jobsStore.status.due }}</strong>
        </div>
      </div>
      <div class="status-side">
        <div class="status-list">
          <span>最近失败</span>
          <template v-if="jobsStore.status.recent_failures.length">
            <code v-for="failure in jobsStore.status.recent_failures.slice(0, 3)" :key="failure.job_id || failure.id || failure.name">
              {{ failure.name || failure.job_id || failure.id }} · {{ failure.last_error || failure.last_delivery_error || failure.last_status }}
            </code>
          </template>
          <code v-else>—</code>
        </div>
        <div class="status-list">
          <span>下次运行</span>
          <template v-if="jobsStore.status.next.length">
            <code v-for="job in jobsStore.status.next.slice(0, 3)" :key="job.id">
              {{ job.name }} · {{ job.next_run_at ? new Date(job.next_run_at).toLocaleString() : '—' }}
            </code>
          </template>
          <code v-else>—</code>
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
  grid-template-columns: repeat(5, minmax(0, 1fr));
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
  grid-template-columns: repeat(2, minmax(0, 1fr));
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
  .status-panel,
  .guide-summary,
  .guide-grid,
  .policy-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .status-grid,
  .status-side {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .guide-meta {
    justify-content: flex-start;
    max-width: none;
  }
}
</style>
