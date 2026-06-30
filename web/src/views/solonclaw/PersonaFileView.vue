<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Button, message } from 'antdv-next'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import MarkdownDocumentPanel from '@/components/solonclaw/markdown/MarkdownDocumentPanel.vue'
import { fetchPersonaFile, personaMeta, savePersonaFile, type PersonaFileData } from '@/api/solonclaw/persona'

const route = useRoute()
const { t } = useI18n()

const loading = ref(false)
const saving = ref(false)
const file = ref<PersonaFileData | null>(null)
const editing = ref(false)
const editContent = ref('')

const fileKey = computed(() => String(route.params.key || 'agents'))
const currentMeta = computed(() => personaMeta(fileKey.value))
const isEmpty = computed(() => !file.value?.content?.trim())
const isReadOnly = computed(() => fileKey.value === 'memory_today')

async function loadFile() {
  loading.value = true
  editing.value = false
  try {
    file.value = await fetchPersonaFile(fileKey.value)
    editContent.value = file.value.content || ''
  } catch (err: any) {
    message.error(err.message || t('personaFile.loadFailed'))
  } finally {
    loading.value = false
  }
}

function startEdit() {
  editContent.value = file.value?.content || ''
  editing.value = true
}

function cancelEdit() {
  editing.value = false
  editContent.value = file.value?.content || ''
}

async function handleSave() {
  saving.value = true
  try {
    await savePersonaFile(fileKey.value, editContent.value)
    await loadFile()
    editing.value = false
    message.success(t('common.saved'))
  } catch (err: any) {
    message.error(err.message || t('common.saveFailed'))
  } finally {
    saving.value = false
  }
}

onMounted(loadFile)
watch(fileKey, loadFile)
</script>

<template>
  <div class="memory-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ currentMeta.title }}</h2>
        <p class="header-subtitle">{{ t('personaFile.subtitle', { description: currentMeta.description, fileName: currentMeta.fileName }) }}</p>
      </div>
      <div class="page-actions">
        <Button size="small" type="text" @click="loadFile">
          <template #icon>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="23 4 23 10 17 10" />
              <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
            </svg>
          </template>
          {{ t('common.refresh') }}
        </Button>
        <Button v-if="!editing && !isReadOnly" size="small" type="text" @click="startEdit">
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
      <MarkdownDocumentPanel
        v-model="editContent"
        :display-content="file?.content || ''"
        :editing="editing"
        :loading="loading && !file"
        :empty="isEmpty"
        :empty-text="t('personaFile.emptyText', { description: currentMeta.description })"
        :placeholder="t('personaFile.editPlaceholder', { title: currentMeta.title })"
        :saving="saving"
        :loading-text="t('common.loading')"
        :cancel-text="t('common.cancel')"
        :save-text="t('common.save')"
        @cancel="cancelEdit"
        @save="handleSave"
      />
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.memory-view {
  height: calc(100 * var(--vh));
  display: flex;
  flex-direction: column;
}

.memory-content {
  flex: 1;
  overflow: hidden;
  padding: 20px;
  display: flex;
  flex-direction: column;
}

.page-actions {
  display: flex;
  gap: 8px;
}
</style>
