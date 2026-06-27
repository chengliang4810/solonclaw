<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useFilesStore } from '@/stores/solonclaw/files'
import FileTree from '@/components/solonclaw/files/FileTree.vue'
import FileBreadcrumb from '@/components/solonclaw/files/FileBreadcrumb.vue'
import FileToolbar from '@/components/solonclaw/files/FileToolbar.vue'
import FileList from '@/components/solonclaw/files/FileList.vue'
import FileContextMenu from '@/components/solonclaw/files/FileContextMenu.vue'
import FileEditor from '@/components/solonclaw/files/FileEditor.vue'
import FilePreview from '@/components/solonclaw/files/FilePreview.vue'
import type { FileEntry } from '@/api/solonclaw/files'

const filesStore = useFilesStore()
const { t } = useI18n()

const contextMenuRef = ref<InstanceType<typeof FileContextMenu> | null>(null)

function handleContextMenu(e: MouseEvent, entry: FileEntry) {
  contextMenuRef.value?.show(e, entry)
}

onMounted(() => {
  filesStore.fetchEntries('')
})
</script>

<template>
  <div class="files-view">
    <header class="page-header files-header">
      <div>
        <h2 class="header-title">{{ t('files.title') }}</h2>
        <p class="header-subtitle">{{ t('files.description') }}</p>
      </div>
    </header>
    <div class="files-shell">
      <div class="files-tree-panel">
        <FileTree />
      </div>
      <div class="files-main-panel">
        <FileToolbar />
        <FileBreadcrumb />
        <div class="files-content">
          <FileEditor v-if="filesStore.editingFile" />
          <FilePreview v-else-if="filesStore.previewFile" />
          <FileList v-else @contextmenu-entry="handleContextMenu" />
        </div>
      </div>
    </div>
    <FileContextMenu ref="contextMenuRef" />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.files-view {
  display: flex;
  flex-direction: column;
  height: calc(100 * var(--vh));
}

.files-header {
  padding-bottom: 0;
}

.files-shell {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.files-tree-panel {
  width: 240px;
  min-width: 180px;
  max-width: 400px;
  border-right: 1px solid $border-color;
  overflow-y: auto;
  flex-shrink: 0;
}

.files-main-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}

.files-content {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

@media (max-width: $breakpoint-mobile) {
  .files-shell {
    flex-direction: column;
  }

  .files-tree-panel {
    width: 100%;
    max-width: none;
    height: 200px;
    border-right: none;
    border-bottom: 1px solid $border-color;
  }
}
</style>
