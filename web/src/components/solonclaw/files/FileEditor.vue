<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { Button, Space, message, Modal } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useFilesStore } from '@/stores/solonclaw/files'
import * as monaco from 'monaco-editor'

// Configure Monaco workers using import.meta.url
;(self as any).MonacoEnvironment = {
  getWorker(_: any, _label: string) {
    return new Worker(
      new URL('monaco-editor/esm/vs/editor/editor.worker.js', import.meta.url),
      { type: 'module' }
    )
  },
}

const { t } = useI18n()
const filesStore = useFilesStore()

const editorContainer = ref<HTMLElement | null>(null)
let editor: monaco.editor.IStandaloneCodeEditor | null = null
const saving = ref(false)
const restoring = ref(false)

onMounted(() => {
  if (!editorContainer.value || !filesStore.editingFile) return

  editor = monaco.editor.create(editorContainer.value, {
    value: filesStore.editingFile.content,
    language: filesStore.editingFile.language,
    theme: document.documentElement.classList.contains('dark') ? 'vs-dark' : 'vs',
    minimap: { enabled: false },
    fontSize: 13,
    lineNumbers: 'on',
    scrollBeyondLastLine: false,
    automaticLayout: true,
    tabSize: 2,
    wordWrap: 'on',
  })

  editor.onDidChangeModelContent(() => {
    if (filesStore.editingFile) {
      filesStore.editingFile.content = editor!.getValue()
    }
  })

  // Ctrl/Cmd + S to save
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
    handleSave()
  })
})

onBeforeUnmount(() => {
  editor?.dispose()
  editor = null
})

async function handleSave() {
  saving.value = true
  try {
    await filesStore.saveEditor()
    message.success(t('files.saved'))
  } catch {
    message.error(t('files.saveFailed'))
  } finally {
    saving.value = false
  }
}

function syncEditorValue() {
  if (editor && filesStore.editingFile) {
    editor.setValue(filesStore.editingFile.content)
  }
}

function handleRestore() {
  Modal.confirm({
    title: t('files.restoreConfirm'),
    okText: t('common.confirm'),
    cancelText: t('common.cancel'),
    onOk: async () => {
      restoring.value = true
      try {
        await filesStore.restoreEditor()
        syncEditorValue()
        message.success(t('files.restored'))
      } catch {
        message.error(t('files.restoreFailed'))
      } finally {
        restoring.value = false
      }
    },
  })
}

function handleClose() {
  if (filesStore.hasUnsavedChanges) {
    Modal.confirm({
      title: t('files.unsavedChanges'),
      okText: t('common.ok'),
      cancelText: t('common.cancel'),
      onOk: () => {
        filesStore.closeEditor()
      },
    })
  } else {
    filesStore.closeEditor()
  }
}
</script>

<template>
  <div class="file-editor">
    <div class="editor-header">
      <span class="editor-filename">{{ filesStore.editingFile?.path }}</span>
      <Space>
        <Button size="small" type="primary" :loading="saving" @click="handleSave">
          {{ t('files.saveFile') }}
        </Button>
        <Button size="small" danger :loading="restoring" @click="handleRestore">
          {{ t('files.restoreDefault') }}
        </Button>
        <Button size="small" @click="handleClose">
          {{ t('files.closeEditor') }}
        </Button>
      </Space>
    </div>
    <div ref="editorContainer" class="editor-container" />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.file-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.editor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  border-bottom: 1px solid $border-color;
  background-color: $bg-card;
}

.editor-filename {
  font-size: 13px;
  color: $text-secondary;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.editor-container {
  flex: 1;
  min-height: 0;
}
</style>
