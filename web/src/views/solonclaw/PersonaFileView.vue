<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
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

/** 标识最近一次文件加载请求，拒绝乱序响应。 */
let loadRequestId = 0
/** 标识最近一次文件保存请求，隔离切页后的迟到完成。 */
let saveRequestId = 0
/** 记录最近请求的文件，用于在切页时同步作废保存状态。 */
let requestedFileKey = ''

const fileKey = computed(() => String(route.params.key || 'agents'))
const currentMeta = computed(() => personaMeta(fileKey.value))
const isEmpty = computed(() => !file.value?.content?.trim())
const isReadOnly = computed(() => fileKey.value === 'memory_today')

async function loadFile() {
  const targetKey = fileKey.value
  const requestId = ++loadRequestId
  if (targetKey !== requestedFileKey) {
    requestedFileKey = targetKey
    saveRequestId += 1
    saving.value = false
  }
  loading.value = true
  editing.value = false
  file.value = null
  editContent.value = ''
  try {
    const result = await fetchPersonaFile(targetKey)
    if (requestId !== loadRequestId || targetKey !== fileKey.value) return
    file.value = result
    editContent.value = result.content || ''
  } catch (err: any) {
    if (requestId === loadRequestId && targetKey === fileKey.value) {
      message.error(err.message || t('personaFile.loadFailed'))
    }
  } finally {
    if (requestId === loadRequestId && targetKey === fileKey.value) loading.value = false
  }
}

function startEdit() {
  const currentFile = file.value
  if (currentFile?.key !== fileKey.value) return
  editContent.value = currentFile.content || ''
  editing.value = true
}

function cancelEdit() {
  editing.value = false
  editContent.value = file.value?.content || ''
}

async function handleSave() {
  const targetKey = fileKey.value
  const targetFile = file.value
  if (!editing.value || targetFile?.key !== targetKey) return
  const content = editContent.value
  const requestId = ++saveRequestId
  saving.value = true
  try {
    await savePersonaFile(targetKey, content)
    if (requestId !== saveRequestId || targetKey !== fileKey.value) return
    await loadFile()
    if (requestId !== saveRequestId || targetKey !== fileKey.value) return
    editing.value = false
    message.success(t('common.saved'))
  } catch (err: any) {
    if (requestId === saveRequestId && targetKey === fileKey.value) {
      message.error(err.message || t('common.saveFailed'))
    }
  } finally {
    if (requestId === saveRequestId && targetKey === fileKey.value) saving.value = false
  }
}

/** 组件卸载时作废所有仍在等待的异步结果。 */
function invalidatePendingRequests(): void {
  loadRequestId += 1
  saveRequestId += 1
}

onMounted(loadFile)
onUnmounted(invalidatePendingRequests)
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
