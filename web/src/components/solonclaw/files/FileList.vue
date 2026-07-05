<script setup lang="ts">
import { Button, Spin, Empty, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useFilesStore, isImageFile, isMarkdownFile, isTextFile } from '@/stores/solonclaw/files'
import { downloadFile } from '@/api/solonclaw/download'
import type { FileEntry } from '@/api/solonclaw/files'
import { formatFileSize } from '@/shared/fileSizeFormat'
import { fileTypeIcon } from '@/shared/fileTypeIcon'
import { formatTimestampText } from '@/shared/timeFormat'

const { t } = useI18n()
const filesStore = useFilesStore()

const emit = defineEmits<{
  (e: 'contextmenu-entry', event: MouseEvent, entry: FileEntry): void
}>()

function handleDoubleClick(entry: FileEntry) {
  if (entry.isDir) {
    filesStore.navigateTo(entry.path)
  } else if (isTextFile(entry.name)) {
    filesStore.openEditor(entry.path)
  } else if (isImageFile(entry.name) || isMarkdownFile(entry.name)) {
    filesStore.openPreview(entry)
  }
}

function handleContextMenu(e: MouseEvent, entry: FileEntry) {
  e.preventDefault()
  emit('contextmenu-entry', e, entry)
}

async function handleDownload(entry: FileEntry) {
  try {
    await downloadFile(entry.path, entry.name)
  } catch (err: unknown) {
    message.error(err instanceof Error && err.message ? err.message : t('files.backendError'))
  }
}
</script>

<template>
  <div class="file-list">
    <Spin :spinning="filesStore.loading">
      <div v-if="filesStore.loadError" class="file-error">
        <strong>{{ t('files.loadFailed') }}</strong>
        <span>{{ filesStore.loadError }}</span>
      </div>
      <Empty v-else-if="!filesStore.loading && filesStore.sortedEntries.length === 0" :description="t('files.emptyDir')" />
      <div v-else class="file-list-items">
        <div class="file-list-header">
          <div class="file-name sort-header" @click="filesStore.setSort('name')">
            {{ t('files.name') }}
            <span v-if="filesStore.sortBy === 'name'" class="sort-indicator">{{ filesStore.sortOrder === 'asc' ? '↑' : '↓' }}</span>
          </div>
          <div class="file-size sort-header" @click="filesStore.setSort('size')">
            {{ t('files.size') }}
            <span v-if="filesStore.sortBy === 'size'" class="sort-indicator">{{ filesStore.sortOrder === 'asc' ? '↑' : '↓' }}</span>
          </div>
          <div class="file-date sort-header" @click="filesStore.setSort('modTime')">
            {{ t('files.modified') }}
            <span v-if="filesStore.sortBy === 'modTime'" class="sort-indicator">{{ filesStore.sortOrder === 'asc' ? '↑' : '↓' }}</span>
          </div>
          <div class="file-actions-placeholder" />
        </div>
        <div
          v-for="entry in filesStore.sortedEntries"
          :key="entry.path"
          class="file-list-row"
          @dblclick="handleDoubleClick(entry)"
          @contextmenu="handleContextMenu($event, entry)"
        >
          <div class="file-name">
            <span class="file-icon">{{ fileTypeIcon(entry) }}</span>
            <span>{{ entry.name }}</span>
          </div>
          <div class="file-size">{{ entry.isDir ? '—' : formatFileSize(entry.size, '—') }}</div>
          <div class="file-date">{{ formatTimestampText(entry.modTime, undefined, '—') }}</div>
          <div class="file-actions">
            <Button v-if="isTextFile(entry.name) && !entry.isDir" size="small" type="text" @click.stop="filesStore.openEditor(entry.path)" :title="t('files.edit')">✏️</Button>
            <Button v-if="!entry.isDir" size="small" type="text" @click.stop="handleDownload(entry)" :title="t('files.download')">⬇️</Button>
          </div>
        </div>
      </div>
    </Spin>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.file-list {
  padding: 8px 16px;
}

.file-error {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 8px 0;
  padding: 10px 12px;
  border: 1px solid rgba(var(--error-rgb), 0.28);
  border-radius: $radius-sm;
  color: $error;
  background: rgba(var(--error-rgb), 0.06);
  font-size: 13px;
}

.file-list-header {
  display: flex;
  align-items: center;
  padding: 6px 12px;
  gap: 16px;
  font-size: 12px;
  font-weight: 500;
  color: $text-muted;
  border-bottom: 1px solid $border-light;
  margin-bottom: 4px;
  user-select: none;
}

.sort-header {
  cursor: pointer;

  &:hover {
    color: $text-primary;
  }
}

.sort-indicator {
  margin-left: 2px;
  font-size: 11px;
}

.file-actions-placeholder {
  width: 60px;
  flex-shrink: 0;
}

.file-list-row {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  border-radius: $radius-sm;
  cursor: pointer;
  gap: 16px;
  font-size: 13px;

  &:hover {
    background-color: rgba(var(--accent-primary-rgb), 0.06);

    .file-actions {
      opacity: 1;
    }
  }
}

.file-name {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-icon {
  flex-shrink: 0;
}

.file-size {
  width: 80px;
  text-align: right;
  color: $text-secondary;
  flex-shrink: 0;
}

.file-date {
  width: 160px;
  color: $text-secondary;
  flex-shrink: 0;
}

.file-actions {
  opacity: 0;
  transition: opacity $transition-fast;
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}

@media (max-width: $breakpoint-mobile) {
  .file-size, .file-date {
    display: none;
  }
}
</style>
