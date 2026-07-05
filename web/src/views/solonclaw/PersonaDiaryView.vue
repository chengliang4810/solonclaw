<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import MarkdownRenderer from '@/components/solonclaw/chat/MarkdownRenderer.vue'
import { fetchPersonaDiaries, fetchPersonaDiary, type PersonaDiaryEntry } from '@/api/solonclaw/persona'

const { t } = useI18n()
const diaries = ref<PersonaDiaryEntry[]>([])
const loading = ref(false)
const selectedPath = ref('')
const content = ref('')
const showSidebar = ref(true)
let mobileQuery: MediaQueryList | null = null

function handleMobileChange(e: MediaQueryListEvent | MediaQueryList) {
  showSidebar.value = !e.matches
}

async function loadDiaryList() {
  loading.value = true
  try {
    diaries.value = await fetchPersonaDiaries()
    if (!selectedPath.value && diaries.value.length > 0) {
      await selectDiary(diaries.value[0].relativePath)
    }
  } finally {
    loading.value = false
  }
}

async function selectDiary(path: string) {
  selectedPath.value = path
  const diary = await fetchPersonaDiary(path)
  content.value = diary.content || ''
  if (window.innerWidth <= 768) {
    showSidebar.value = false
  }
}

onMounted(() => {
  mobileQuery = window.matchMedia('(max-width: 768px)')
  handleMobileChange(mobileQuery)
  mobileQuery.addEventListener('change', handleMobileChange)
  loadDiaryList()
})

onUnmounted(() => {
  mobileQuery?.removeEventListener('change', handleMobileChange)
})
</script>

<template>
  <div class="skills-view">
    <header class="page-header">
      <div>
        <div style="display: flex; align-items: center; gap: 8px;">
          <h2 class="header-title">{{ t('personaDiary.title') }}</h2>
          <button v-if="!showSidebar" class="sidebar-toggle" @click="showSidebar = true">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
          </button>
        </div>
        <p class="header-subtitle">{{ t('personaDiary.description') }}</p>
      </div>
    </header>

    <div class="skills-content">
      <div v-if="loading && diaries.length === 0" class="skills-loading">{{ t('common.loading') }}</div>
      <div v-else class="skills-layout">
        <div class="mobile-backdrop" :class="{ active: showSidebar }" @click="showSidebar = false" />
        <div v-if="showSidebar" class="skills-sidebar">
          <div class="diary-list">
            <button
              v-for="diary in diaries"
              :key="diary.relativePath"
              class="diary-item"
              :class="{ active: selectedPath === diary.relativePath }"
              @click="selectDiary(diary.relativePath)"
            >
              {{ diary.name.replace('.md', '') }}
            </button>
          </div>
        </div>
        <div class="skills-main">
          <div v-if="selectedPath" class="diary-detail">
            <div class="detail-title">{{ selectedPath.split('/').pop()?.replace('.md', '') }}</div>
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

.detail-title {
  padding-bottom: 12px;
  border-bottom: 1px solid $border-color;
  margin-bottom: 12px;
  font-size: 15px;
  font-weight: 600;
  color: $text-primary;
}

.detail-content {
  min-height: 0;
}

</style>
