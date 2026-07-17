<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import MarkdownRenderer from '@/components/solonclaw/chat/MarkdownRenderer.vue'
import {
  fetchMemoryArchiveState,
  fetchPersonaDiaries,
  fetchPersonaDiary,
  restoreMemoryArchive,
  runMemoryArchive,
  type MemoryArchiveState,
  type PersonaDiaryEntry,
} from '@/api/solonclaw/persona'

const { t } = useI18n()
const diaries = ref<PersonaDiaryEntry[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
const selectedPath = ref('')
const content = ref('')
const showSidebar = ref(true)
const archiveState = ref<MemoryArchiveState | null>(null)
const archiveBusy = ref(false)
let mobileQuery: MediaQueryList | null = null

const activeDiaries = computed(() => diaries.value.filter((item) => item.kind === 'active'))
const archivedDiaries = computed(() => diaries.value.filter((item) => item.kind !== 'active'))
const selectedDiary = computed(() => diaries.value.find((item) => item.relativePath === selectedPath.value))

function handleMobileChange(e: MediaQueryListEvent | MediaQueryList) {
  showSidebar.value = !e.matches
}

async function loadDiaryList() {
  loading.value = true
  loadError.value = null
  try {
    diaries.value = await fetchPersonaDiaries()
    if (!selectedPath.value && diaries.value.length > 0) {
      await selectDiary(diaries.value[0].relativePath)
    }
  } catch (err: any) {
    loadError.value = err?.message || t('personaDiary.loadFailed')
  } finally {
    loading.value = false
  }
}

async function loadArchiveState() {
  try {
    archiveState.value = await fetchMemoryArchiveState()
  } catch (err: any) {
    loadError.value = err?.message || t('personaDiary.archiveFailed')
  }
}

async function runArchiveNow() {
  archiveBusy.value = true
  loadError.value = null
  try {
    archiveState.value = await runMemoryArchive()
    selectedPath.value = ''
    content.value = ''
    await loadDiaryList()
  } catch (err: any) {
    loadError.value = err?.message || t('personaDiary.archiveFailed')
  } finally {
    archiveBusy.value = false
  }
}

async function restoreSelectedArchive() {
  if (selectedDiary.value?.kind !== 'archive') return
  archiveBusy.value = true
  loadError.value = null
  try {
    await restoreMemoryArchive(selectedDiary.value.relativePath)
    await loadDiaryList()
  } catch (err: any) {
    loadError.value = err?.message || t('personaDiary.restoreFailed')
  } finally {
    archiveBusy.value = false
  }
}

function diaryLabel(diary: PersonaDiaryEntry) {
  const name = diary.name.replace('.summary.md', '').replace('.md', '').replace(/--[0-9a-f]{12}$/, '')
  if (diary.kind === 'archive') return `${name} · ${t('personaDiary.original')}`
  if (diary.kind === 'summary') return `${name} · ${t('personaDiary.summary')}`
  return name
}

async function selectDiary(path: string) {
  selectedPath.value = path
  loadError.value = null
  try {
    const diary = await fetchPersonaDiary(path)
    content.value = diary.content || ''
  } catch (err: any) {
    loadError.value = err?.message || t('personaDiary.loadFailed')
    return
  }
  if (window.innerWidth <= 768) {
    showSidebar.value = false
  }
}

onMounted(() => {
  mobileQuery = window.matchMedia('(max-width: 768px)')
  handleMobileChange(mobileQuery)
  mobileQuery.addEventListener('change', handleMobileChange)
  loadDiaryList()
  loadArchiveState()
})

onUnmounted(() => {
  mobileQuery?.removeEventListener('change', handleMobileChange)
})
</script>

<template>
  <div class="skills-view">
    <header class="page-header">
      <div class="header-copy">
        <div style="display: flex; align-items: center; gap: 8px;">
          <h2 class="header-title">{{ t('personaDiary.title') }}</h2>
          <button v-if="!showSidebar" class="sidebar-toggle" @click="showSidebar = true">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
          </button>
        </div>
        <p class="header-subtitle">{{ t('personaDiary.description') }}</p>
      </div>
      <div class="archive-actions">
        <span v-if="archiveState" class="archive-status">{{ t('personaDiary.archiveStatus') }} · {{ archiveState.lastOutcome }}</span>
        <button class="icon-command" :disabled="archiveBusy" :title="t('personaDiary.runArchive')" @click="runArchiveNow">
          <span v-if="archiveBusy" class="spinner" aria-hidden="true" />
          <span v-else class="command-symbol" aria-hidden="true">▶</span>
          <span>{{ t('personaDiary.runArchive') }}</span>
        </button>
      </div>
    </header>

    <div class="skills-content">
      <div v-if="loading && diaries.length === 0" class="skills-loading">{{ t('common.loading') }}</div>
      <div v-else class="skills-layout">
        <div class="mobile-backdrop" :class="{ active: showSidebar }" @click="showSidebar = false" />
        <div v-if="showSidebar" class="skills-sidebar">
          <div v-if="loadError" class="diary-load-error">
            <strong>{{ t('personaDiary.loadFailed') }}</strong>
            <span>{{ loadError }}</span>
          </div>
          <div class="diary-list">
            <div v-if="activeDiaries.length" class="diary-group-title">{{ t('personaDiary.activeGroup') }}</div>
            <button
              v-for="diary in activeDiaries"
              :key="diary.relativePath"
              class="diary-item"
              :class="{ active: selectedPath === diary.relativePath }"
              @click="selectDiary(diary.relativePath)"
            >
              {{ diaryLabel(diary) }}
            </button>
            <div v-if="archivedDiaries.length" class="diary-group-title archived">
              <span aria-hidden="true">▣</span>
              {{ t('personaDiary.archiveGroup') }}
            </div>
            <button
              v-for="diary in archivedDiaries"
              :key="diary.relativePath"
              class="diary-item archived-item"
              :class="{ active: selectedPath === diary.relativePath }"
              @click="selectDiary(diary.relativePath)"
            >
              {{ diaryLabel(diary) }}
            </button>
          </div>
        </div>
        <div class="skills-main">
          <div v-if="selectedPath" class="diary-detail">
            <div class="detail-header">
              <div class="detail-title">{{ selectedDiary ? diaryLabel(selectedDiary) : '' }}</div>
              <button
                v-if="selectedDiary?.kind === 'archive'"
                class="icon-command secondary"
                :disabled="archiveBusy"
                :title="t('personaDiary.restoreArchive')"
                @click="restoreSelectedArchive"
              >
                <span class="command-symbol" aria-hidden="true">↶</span>
                <span>{{ t('personaDiary.restoreArchive') }}</span>
              </button>
            </div>
            <div class="detail-content">
              <MarkdownRenderer v-if="content.trim()" :content="content" />
              <div v-else class="empty-detail">{{ t('personaDiary.emptyDay') }}</div>
            </div>
          </div>
          <div v-else class="empty-detail">{{ t('personaDiary.emptyAll') }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.diary-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.page-header,
.archive-actions,
.detail-header,
.diary-group-title {
  display: flex;
  align-items: center;
}

.page-header {
  justify-content: space-between;
  gap: 16px;
}

.header-copy {
  min-width: 0;
}

.archive-actions {
  flex-shrink: 0;
  gap: 10px;
}

.archive-status {
  color: $text-secondary;
  font-size: 12px;
}

.icon-command {
  display: inline-flex;
  min-height: 32px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 6px 10px;
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  background: $accent-primary;
  color: white;
  cursor: pointer;

  &:disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }

  &.secondary {
    background: transparent;
    color: $text-primary;
  }
}

.diary-group-title {
  gap: 5px;
  padding: 8px 10px 5px;
  color: $text-secondary;
  font-size: 11px;
  font-weight: 600;

  &.archived {
    margin-top: 8px;
    border-top: 1px solid $border-color;
    padding-top: 13px;
  }
}

.diary-item {
  display: flex;
  width: 100%;
  padding: 8px 10px;
  border: none;
  background: none;
  color: $text-secondary;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  border-radius: $radius-sm;
  transition: all $transition-fast;

  &:hover {
    background: rgba(var(--accent-primary-rgb), 0.06);
    color: $text-primary;
  }

  &.active {
    background: rgba(var(--accent-primary-rgb), 0.1);
    color: $text-primary;
    font-weight: 500;
  }
}

.archived-item {
  color: $text-secondary;
  opacity: 0.82;
}

.detail-header {
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid $border-color;
  margin-bottom: 12px;
}

.detail-title {
  min-width: 0;
  font-size: 15px;
  font-weight: 600;
  color: $text-primary;
}

.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

.command-symbol {
  width: 14px;
  line-height: 1;
  text-align: center;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 768px) {
  .page-header {
    align-items: flex-start;
  }

  .archive-actions {
    align-items: flex-end;
    flex-direction: column;
  }

  .archive-status {
    display: none;
  }
}

.detail-content {
  min-height: 0;
}

.diary-load-error {
  margin: 8px;
  padding: 10px;
  border: 1px solid rgba($error, 0.25);
  border-radius: $radius-sm;
  background: rgba($error, 0.06);
  color: $error;
  font-size: 12px;

  strong,
  span {
    display: block;
  }

  span {
    margin-top: 4px;
    color: $text-secondary;
    word-break: break-word;
  }
}

</style>
