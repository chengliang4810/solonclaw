<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Button, Spin, Tag, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import {
  fetchCuratorReport,
  fetchCuratorImprovements,
  fetchCuratorReports,
  markCuratorSuggestion,
  runCurator,
  type CuratorImprovement,
  type CuratorReportDetail,
  type CuratorReportSummary,
} from '@/api/solonclaw/curator'
import { formatTimestampText } from '@/shared/timeFormat'

const { t } = useI18n()
const reports = ref<CuratorReportSummary[]>([])
const improvements = ref<CuratorImprovement[]>([])
const selected = ref<CuratorReportDetail | null>(null)
const loading = ref(false)
const running = ref(false)
const markingId = ref('')

const selectedJson = computed(() => {
  if (!selected.value) return ''
  return JSON.stringify(selected.value.report ?? selected.value.report_json ?? selected.value, null, 2)
})

function statusColor(status?: string) {
  if (status === 'completed' || status === 'success') return 'success'
  if (status === 'failed' || status === 'error') return 'error'
  return 'default'
}

async function loadReports(selectFirst = false) {
  loading.value = true
  try {
    const [nextReports, nextImprovements] = await Promise.all([
      fetchCuratorReports(30),
      fetchCuratorImprovements(20),
    ])
    reports.value = nextReports
    improvements.value = nextImprovements
    if (selectFirst && reports.value[0]) {
      await selectReport(reports.value[0])
    }
  } catch (e: any) {
    message.error(e.message || t('common.fetchFailed'))
  } finally {
    loading.value = false
  }
}

async function selectReport(report: CuratorReportSummary) {
  selected.value = await fetchCuratorReport(report.report_id)
}

async function handleRun() {
  running.value = true
  try {
    const result = await runCurator(true)
    message.success(result.summary || t('curator.runStarted'))
    await loadReports(true)
  } catch (e: any) {
    message.error(e.message || t('curator.runFailed'))
  } finally {
    running.value = false
  }
}

async function handleSuggestion(item: CuratorImprovement, action: 'apply' | 'ignore') {
  markingId.value = `${item.improvement_id}:${action}`
  try {
    await markCuratorSuggestion(item.skill_name, item.summary || item.action || item.improvement_id, action)
    message.success(action === 'apply' ? t('curator.markAppliedSuccess') : t('curator.markIgnoredSuccess'))
    improvements.value = await fetchCuratorImprovements(20)
  } catch (e: any) {
    message.error(e.message || t('curator.markFailed'))
  } finally {
    markingId.value = ''
  }
}

onMounted(() => loadReports(true))
</script>

<template>
  <div class="curator-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('curator.title') }}</h2>
        <p class="header-subtitle">{{ t('curator.description') }}</p>
      </div>
      <div class="header-actions">
        <Button size="small" :loading="loading" @click="loadReports(false)">{{ t('common.refresh') }}</Button>
        <Button size="small" type="primary" :loading="running" @click="handleRun">{{ t('curator.run') }}</Button>
      </div>
    </header>

    <Spin :spinning="loading">
      <main class="curator-body">
        <section class="report-list">
          <button
            v-for="report in reports"
            :key="report.report_id"
            class="report-row"
            :class="{ active: selected?.report_id === report.report_id }"
            @click="selectReport(report)"
          >
            <div class="report-row-head">
              <span class="report-id">{{ report.report_id }}</span>
              <Tag size="small" :color="statusColor(report.status)" :bordered="false">{{ report.status || '-' }}</Tag>
            </div>
            <p>{{ report.summary || t('curator.noSummary') }}</p>
            <span class="report-time">{{ formatTimestampText(report.started_at) }}</span>
          </button>
          <div v-if="!reports.length && !loading" class="empty-state">{{ t('curator.empty') }}</div>
        </section>

        <section class="report-detail">
          <template v-if="selected">
            <div class="detail-head">
              <div>
                <h3>{{ selected.report_id }}</h3>
                <p>{{ selected.summary || t('curator.noSummary') }}</p>
              </div>
              <Tag :color="statusColor(selected.status)" :bordered="false">{{ selected.status || '-' }}</Tag>
            </div>
            <div class="detail-meta">
              <span>{{ t('curator.startedAt') }} {{ formatTimestampText(selected.started_at) }}</span>
              <span>{{ t('curator.finishedAt') }} {{ formatTimestampText(selected.finished_at) }}</span>
              <span>{{ selected.report_path || '-' }}</span>
            </div>
            <pre>{{ selectedJson }}</pre>
          </template>
          <div v-else class="empty-state">{{ t('curator.selectReport') }}</div>
        </section>

        <section class="improvement-list">
          <div class="section-head">
            <h3>{{ t('curator.improvements') }}</h3>
            <Tag size="small" :bordered="false">{{ improvements.length }}</Tag>
          </div>
          <article v-for="item in improvements" :key="item.improvement_id" class="improvement-row">
            <div class="report-row-head">
              <span class="report-id">{{ item.skill_name || '-' }}</span>
              <Tag size="small" :color="item.needs_review ? 'warning' : 'default'" :bordered="false">
                {{ item.action || '-' }}
              </Tag>
            </div>
            <p>{{ item.summary || t('curator.noSummary') }}</p>
            <div class="improvement-actions">
              <Button size="small" type="text" :loading="markingId === `${item.improvement_id}:apply`" @click="handleSuggestion(item, 'apply')">
                {{ t('curator.markApplied') }}
              </Button>
              <Button size="small" type="text" :loading="markingId === `${item.improvement_id}:ignore`" @click="handleSuggestion(item, 'ignore')">
                {{ t('curator.markIgnored') }}
              </Button>
            </div>
          </article>
          <div v-if="!improvements.length && !loading" class="empty-state">{{ t('curator.noImprovements') }}</div>
        </section>
      </main>
    </Spin>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.curator-view {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.curator-body {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 16px;
  padding: 20px;
  height: calc(100 * var(--vh) - 82px);
}

.report-list,
.report-detail,
.improvement-list {
  min-height: 0;
  overflow: auto;
  border: 1px solid $border-color;
  background: $bg-card;
  border-radius: $radius-sm;
}

.report-row {
  display: block;
  width: 100%;
  padding: 14px;
  border: 0;
  border-bottom: 1px solid $border-light;
  background: transparent;
  color: $text-primary;
  text-align: left;
  cursor: pointer;
}

.report-row:hover,
.report-row.active {
  background: $bg-card-hover;
}

.report-row-head,
.detail-head,
.detail-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.report-id {
  font-family: $font-code;
  font-size: 12px;
}

.report-row p,
.detail-head p {
  margin-top: 8px;
  color: $text-secondary;
  font-size: 13px;
}

.report-time,
.detail-meta {
  display: block;
  margin-top: 8px;
  color: $text-muted;
  font-size: 12px;
}

.report-detail {
  padding: 16px;
}

.detail-head h3 {
  font-size: 15px;
  font-family: $font-code;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px;
  border-bottom: 1px solid $border-light;
}

.section-head h3 {
  font-size: 14px;
}

.improvement-row {
  padding: 14px;
  border-bottom: 1px solid $border-light;
}

.improvement-row p {
  margin-top: 8px;
  color: $text-secondary;
  font-size: 13px;
}

.improvement-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.detail-meta {
  justify-content: flex-start;
  flex-wrap: wrap;
  margin: 14px 0;
}

pre {
  overflow: auto;
  padding: 14px;
  border-radius: $radius-sm;
  background: $code-bg;
  color: $text-secondary;
  font-size: 12px;
  line-height: 1.55;
}

.empty-state {
  padding: 40px 16px;
  color: $text-muted;
  text-align: center;
}

@media (max-width: $breakpoint-mobile) {
  .curator-body {
    grid-template-columns: 1fr;
    height: auto;
  }
}
</style>
