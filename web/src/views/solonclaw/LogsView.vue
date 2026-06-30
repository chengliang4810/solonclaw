<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Select, Button, Spin, message } from 'antdv-next'
import type { SelectValue } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { fetchLogFiles, fetchLogs, type LogEntry } from '@/api/solonclaw/logs'
import { LOG_LINE_COUNT_OPTIONS, logLevelClass, translateLogLevelOptions } from '@/shared/logViewOptions'

const { t } = useI18n()
const logFiles = ref<{ name: string; size: string; modified: string }[]>([])
const selectedLog = ref('agent')
const entries = ref<LogEntry[]>([])
const loading = ref(false)
const lineCount = ref(100)
const levelFilter = ref<string>('')
const componentFilter = ref('')
const searchQuery = ref('')
const appliedSearchQuery = ref('')

const logOptions = computed(() =>
  logFiles.value.map(f => ({ label: `${f.name} (${f.size})`, value: f.name })),
)

const levelOptions = computed(() => translateLogLevelOptions(t))
const lineOptions = computed(() => LOG_LINE_COUNT_OPTIONS.map(option => ({ ...option })))

const filteredEntries = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q || q === appliedSearchQuery.value.toLowerCase()) return entries.value
  return entries.value.filter(e =>
    e.message.toLowerCase().includes(q) ||
    e.logger.toLowerCase().includes(q) ||
    e.raw.toLowerCase().includes(q),
  )
})

function formatTime(ts: string): string {
  const match = ts.match(/\d{2}:\d{2}:\d{2}/)
  return match ? match[0] : ts
}

interface AccessLogSummary {
  readonly method: string
  readonly path: string
  readonly status: string
}

interface DisplayLogEntry extends LogEntry {
  readonly access: AccessLogSummary | null
}

const displayEntries = computed<DisplayLogEntry[]>(() =>
  filteredEntries.value.map(entry => ({
    ...entry,
    access: parseAccessLog(entry.message),
  })),
)

function parseAccessLog(msg: string): AccessLogSummary | null {
  const match = msg.match(/"(\w+)\s+(\S+)\s+HTTP\/[^"]+"\s+(\d+)/)
  if (!match) return null
  const [, method, path, status] = match
  if (!method || !path || !status) return null
  return { method, path, status }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

async function loadLogs() {
  loading.value = true
  try {
    const query = searchQuery.value.trim()
    const data = await fetchLogs(selectedLog.value, {
      lines: lineCount.value,
      level: levelFilter.value || undefined,
      component: componentFilter.value.trim() || undefined,
      query: query || undefined,
    })
    entries.value = data.filter((e): e is LogEntry => e !== null)
    appliedSearchQuery.value = query
  } catch (error: unknown) {
    message.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function handleLevelChange(value: SelectValue) {
  levelFilter.value = typeof value === 'string' ? value : ''
  loadLogs()
}

function handleLineCountChange(value: SelectValue) {
  if (typeof value !== 'number') return
  lineCount.value = value
  loadLogs()
}

onMounted(async () => {
  logFiles.value = await fetchLogFiles()
  await loadLogs()
})
</script>

<template>
  <div class="logs-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('logs.title') }}</h2>
        <p class="header-subtitle">{{ t('logs.description') }}</p>
      </div>
      <div class="header-actions">
        <Select
          v-model:value="selectedLog"
          :options="logOptions"
          size="small"
          class="input-md"
          @update:value="loadLogs"
        />
        <Select
          :value="levelFilter"
          :options="levelOptions"
          size="small"
          class="input-sm"
          @update:value="handleLevelChange"
        />
        <Select
          :value="lineCount"
          :options="lineOptions"
          size="small"
          class="input-sm"
          @update:value="handleLineCountChange"
        />
        <input
          v-model="componentFilter"
          class="component-input"
          :placeholder="t('logs.componentPlaceholder')"
          @keydown.enter="loadLogs"
        />
        <input
          v-model="searchQuery"
          class="search-input"
          :placeholder="t('logs.searchPlaceholder')"
          @keydown.enter="loadLogs"
        />
        <Button size="small" :loading="loading" @click="loadLogs">{{ t('logs.refresh') }}</Button>
      </div>
    </header>

    <div class="logs-body">
      <Spin :spinning="loading">
        <div v-if="displayEntries.length === 0 && !loading" class="logs-empty">
          {{ t('logs.noEntries') }}
        </div>
        <div class="log-list">
          <div
            v-for="(entry, idx) in displayEntries"
            :key="idx"
            class="log-entry"
            :class="logLevelClass(entry.level)"
          >
            <span class="log-time">{{ formatTime(entry.timestamp) }}</span>
            <span class="log-level" :class="logLevelClass(entry.level)">{{ entry.level }}</span>
            <span class="log-logger">{{ entry.logger }}</span>
            <template v-if="entry.access">
              <span class="access-method">{{ entry.access.method }}</span>
              <span class="access-path">{{ entry.access.path }}</span>
              <span class="access-status" :class="'status-' + (entry.access.status[0] || 'x')">
                {{ entry.access.status }}
              </span>
            </template>
            <span v-else class="log-message">{{ entry.message }}</span>
          </div>
        </div>
      </Spin>
    </div>
  </div>
</template>

<style scoped lang="scss" src="../../styles/logsView.scss"></style>
