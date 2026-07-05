<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { Button, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import MarkdownDocumentPanel from '@/components/solonclaw/markdown/MarkdownDocumentPanel.vue'
import { fetchMemory, saveMemory, type MemoryData } from '@/api/solonclaw/skills'

const { t } = useI18n()
const loading = ref(false)
const data = ref<MemoryData | null>(null)
const loadError = ref<string | null>(null)
const editingSection = ref<'memory' | null>(null)
const editContent = ref('')
const saving = ref(false)

onMounted(loadMemory)

async function loadMemory() {
  loading.value = true
  loadError.value = null
  try {
    data.value = await fetchMemory()
  } catch (err: any) {
    console.error('Failed to load memory:', err)
    loadError.value = err instanceof Error ? err.message : String(err || t('memory.loadFailed'))
  } finally {
    loading.value = false
  }
}

function startEdit(section: 'memory') {
  editingSection.value = section
  editContent.value = data.value?.[section] || ''
}

function cancelEdit() {
  editingSection.value = null
  editContent.value = ''
}

async function handleSave() {
  if (!editingSection.value) return
  saving.value = true
  try {
    await saveMemory(editingSection.value, editContent.value)
    await loadMemory()
    editingSection.value = null
    editContent.value = ''
    message.success(t('common.saved'))
  } catch (err: any) {
    message.error(`${t('common.saveFailed')}: ${err.message}`)
  } finally {
    saving.value = false
  }
}

function formatTime(ts: number | null): string {
  if (!ts) return ''
  return new Date(ts).toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

const memoryEmpty = computed(() => !data.value?.memory?.trim())
const displayMemory = computed(() => (data.value?.memory || '').replace(/§/g, '\n\n'))
</script>

<template>
  <div class="memory-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('memory.title') }}</h2>
        <p class="header-subtitle">{{ t('memory.description') }}</p>
      </div>
      <div class="page-actions">
        <Button size="small" type="text" @click="loadMemory">
          <template #icon>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="23 4 23 10 17 10" />
              <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
            </svg>
          </template>
          {{ t('memory.refresh') }}
        </Button>
        <Button v-if="editingSection !== 'memory'" size="small" type="text" @click="startEdit('memory')">
          <template #icon>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
              <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
            </svg>
          </template>
          {{ t('common.edit') }}
        </Button>
      </div>
    </header>

    <div class="memory-content">
      <div v-if="loadError" class="memory-load-error">
        <strong>{{ t('memory.loadFailed') }}</strong>
        <span>{{ loadError }}</span>
      </div>
      <MarkdownDocumentPanel
        v-if="!loadError || data"
        v-model="editContent"
        :display-content="displayMemory"
        :editing="editingSection === 'memory'"
        :loading="loading && !data"
        :empty="memoryEmpty"
        :empty-text="t('memory.noNotes')"
        :placeholder="t('memory.notesPlaceholder')"
        :saving="saving"
        :loading-text="t('common.loading')"
        :cancel-text="t('common.cancel')"
        :save-text="t('common.save')"
        density="compact"
        @cancel="cancelEdit"
        @save="handleSave"
      >
        <template #intro>
          <div class="memory-intro">
            <div class="memory-intro-title">{{ t('memory.myNotes') }}</div>
            <div class="memory-intro-desc">{{ t('memory.notesDescription') }}</div>
            <div v-if="data?.memory_mtime" class="memory-intro-meta">
              {{ t('memory.updatedAt', { time: formatTime(data.memory_mtime) }) }}
            </div>
          </div>
        </template>
      </MarkdownDocumentPanel>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.memory-intro {
  padding: 16px 20px 0;
}

.memory-intro-title {
  font-size: 14px;
  font-weight: 600;
  color: $text-primary;
}

.memory-intro-desc {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: $text-secondary;
}

.memory-intro-meta {
  margin-top: 6px;
  font-size: 11px;
  color: $text-muted;
}

.memory-load-error {
  display: grid;
  gap: 4px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid rgba(var(--error-rgb), 0.28);
  border-radius: $radius-sm;
  background: rgba(var(--error-rgb), 0.06);
  color: $error;
  font-size: 13px;

  span {
    overflow-wrap: anywhere;
  }
}
</style>
