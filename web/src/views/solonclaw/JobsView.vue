<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { Button, Spin } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import JobsPanel from '@/components/solonclaw/jobs/JobsPanel.vue'
import JobFormModal from '@/components/solonclaw/jobs/JobFormModal.vue'
import { useJobsStore } from '@/stores/solonclaw/jobs'
import { formatJobTime, jobMapKeysText, jobTokenListText, jobValueList, jobValueText } from '@/shared/jobsDisplay'

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
  return jobTokenListText(t, value, { guide: true })
}

function mapKeys(value?: Record<string, unknown>): string {
  return jobMapKeysText(t, value)
}

function valueText(value: unknown): string {
  return jobValueText(t, value)
}

function valueList(value: unknown): string[] {
  return jobValueList(t, value)
}

function policyFlags(): string[] {
  const flags: string[] = []
  if (policy.value?.sourceScopedList) flags.push(t('jobs.policyFlag.sourceScopedList'))
  if (policy.value?.freshSessionRuns) flags.push(t('jobs.policyFlag.freshSessionRuns'))
  if (policy.value?.runtime_isolation?.sourceBoundSessionRuns) flags.push(t('jobs.policyFlag.sourceBoundSessionRuns'))
  if (policy.value?.runtime_isolation?.autoDeliveryContext) flags.push(t('jobs.policyFlag.autoDeliveryContext'))
  if (policy.value?.selfContainedPromptRequired) flags.push(t('jobs.policyFlag.selfContainedPromptRequired'))
  if (policy.value?.recursiveCronCreationDiscouraged) flags.push(t('jobs.policyFlag.recursiveCronCreationDiscouraged'))
  return flags
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

    <section v-if="guide" class="guide-panel" :class="{ expanded: showGuide }">
      <div class="guide-summary">
        <div>
          <div class="guide-title">{{ t('jobs.pageGuideTitle') }}</div>
          <div class="guide-objective">{{ guide.objective }}</div>
        </div>
        <div class="guide-meta">
          <span>{{ listText(guide.schedule_types) }}</span>
          <span>{{ t('jobs.pageGuideMetaFields', { count: guide.editable_fields.length }) }}</span>
          <span>{{ mapKeys(guide.actions) }}</span>
        </div>
        <Button size="small" type="text" @click="showGuide = !showGuide">
          {{ showGuide ? t('jobs.pageGuideCollapse') : t('jobs.pageGuideExpand') }}
        </Button>
      </div>
      <div v-if="showGuide" class="guide-body">
        <div class="guide-grid">
          <section class="guide-block">
            <span>{{ t('jobs.guideDelivery') }}</span>
            <strong>{{ valueText(guide.delivery.targets) }}</strong>
            <p>{{ valueText(guide.delivery.multi_target) }}</p>
          </section>
          <section class="guide-block">
            <span>{{ t('jobs.skills') }}</span>
            <strong>{{ valueText(guide.skill_binding.replace) }}</strong>
            <p>{{ t('jobs.pageSkillMergeSummary', { append: valueText(guide.skill_binding.append), remove: valueText(guide.skill_binding.remove) }) }}</p>
          </section>
          <section class="guide-block">
            <span>{{ t('jobs.detail.config') }}</span>
            <strong>{{ t('jobs.pageRuntimeAgentMode') }}</strong>
            <p>{{ valueText(guide.runtime_modes.no_agent) }}</p>
          </section>
          <section class="guide-block">
            <span>{{ t('jobs.pageRuntimeIsolation') }}</span>
            <strong>{{ valueText(guide.runtime_modes.session_binding) }}</strong>
            <p>{{ t('jobs.pageRuntimeDisabledToolsets', { toolsets: valueText(guide.runtime_modes.disabled_toolsets) }) }}</p>
          </section>
          <section class="guide-block">
            <span>{{ t('jobs.pageSecurityPolicy') }}</span>
            <strong>{{ valueText(guide.security.prompt_scan) }}</strong>
            <p>{{ valueText(guide.security.script_validation) }}</p>
          </section>
        </div>
        <div v-if="policy" class="policy-grid">
          <section class="guide-block">
            <span>{{ t('jobs.pageActionSyntax') }}</span>
            <code v-for="syntax in Object.values(policy.action_syntax || {}).slice(0, 4)" :key="syntax">
              {{ syntax }}
            </code>
          </section>
          <section class="guide-block">
            <span>{{ t('jobs.pageUpdatableFields') }}</span>
            <p>{{ valueText(policy.update_fields) }}</p>
          </section>
          <section class="guide-block">
            <span>{{ t('jobs.pageClearableFields') }}</span>
            <p>{{ valueText(policy.clear_fields) }}</p>
          </section>
          <section class="guide-block">
            <span>{{ t('jobs.pageExecutionPolicy') }}</span>
            <p>{{ valueText(policyFlags()) }}</p>
            <p>{{ t('jobs.pageExecutionPolicySummary', { approval: valueText(policy.execution?.dangerousCommandApprovalApplied), history: valueText(policy.execution?.historySupported) }) }}</p>
          </section>
          <section class="guide-block">
            <span>{{ t('jobs.pageRuntimeIsolation') }}</span>
            <p>{{ t('jobs.pageIsolationSummary', { toolsets: valueText(policy.runtime_isolation?.disabledToolsets) }) }}</p>
            <p>{{ t('jobs.pageIsolationTimeout', { seconds: valueText(policy.runtime_isolation?.inactivityTimeoutSeconds), localOnly: valueText(policy.runtime_isolation?.localDeliveryHistoryOnly) }) }}</p>
          </section>
          <section class="guide-block">
            <span>{{ t('jobs.pageDeliveryCapabilities') }}</span>
            <p>{{ valueText(policy.delivery?.targetForms) }}</p>
            <p>{{ valueText(policy.delivery?.wrapFlags) }}</p>
          </section>
          <section class="guide-block">
            <span>{{ t('jobs.pageSkillsAndDeps') }}</span>
            <p>{{ valueText(policy.skill_binding?.appendFlags) }}</p>
            <p>{{ valueText(policy.skill_binding?.dependencyFlags) }}</p>
          </section>
          <section class="guide-block">
            <span>{{ t('jobs.pageScheduleCapabilities') }}</span>
            <p>{{ valueText(valueList(policy.schedule?.cronExpressionSupported).length ? valueList(policy.schedule?.cronExpressionSupported) : ['cron', 'interval', 'once']) }}</p>
          </section>
          <section class="guide-block">
            <span>{{ t('jobs.pageHistoryFields') }}</span>
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
          <span>{{ t('jobs.pageTotal') }}</span>
          <strong>{{ jobsStore.status.total }}</strong>
        </div>
        <div class="status-item">
          <span>{{ t('jobs.pageActive') }}</span>
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
          <span>{{ t('jobs.pageDue') }}</span>
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
        <div class="status-list">
          <span>{{ t('jobs.pageNextRuns') }}</span>
          <template v-if="jobsStore.status.next.length">
            <code v-for="job in jobsStore.status.next.slice(0, 3)" :key="job.id">
              {{ job.name }} · {{ formatJobTime(job.next_run_at) }}
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
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
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

  > div {
    min-width: 0;
  }
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
  min-width: 0;

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
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px;
}

.policy-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
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

@media (max-width: 900px) {
  .status-panel {
    grid-template-columns: 1fr;
  }

  .guide-summary {
    grid-template-columns: 1fr;
    align-items: start;
  }

  .guide-meta {
    justify-content: flex-start;
    max-width: none;

    span {
      max-width: 100%;
      overflow-wrap: anywhere;
      white-space: normal;
    }
  }
}

@media (max-width: $breakpoint-mobile) {
  .jobs-content {
    padding: 12px;
  }

  .status-panel,
  .guide-panel {
    margin-left: 12px;
    margin-right: 12px;
  }

  .guide-summary {
    gap: 10px;
  }

  .guide-meta span {
    width: 100%;
  }

  .status-grid,
  .status-side,
  .guide-grid,
  .policy-grid {
    grid-template-columns: 1fr;
  }
}
</style>
