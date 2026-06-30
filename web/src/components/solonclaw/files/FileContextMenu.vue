<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { Dropdown, message, Modal } from 'antdv-next'
import type { MenuProps } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useFilesStore } from '@/stores/solonclaw/files'
import { downloadFile } from '@/api/solonclaw/download'
import type { FileEntry } from '@/api/solonclaw/files'
import { buildFileContextMenuItems } from '@/shared/fileContextMenu'
import { copyToClipboard } from '@/utils/clipboard'

const { t } = useI18n()
const filesStore = useFilesStore()

const showMenu = ref(false)
const menuX = ref(0)
const menuY = ref(0)
const targetEntry = ref<FileEntry | null>(null)

function show(e: MouseEvent, entry: FileEntry) {
  targetEntry.value = entry
  menuX.value = e.clientX
  menuY.value = e.clientY
  showMenu.value = false
  nextTick(() => {
    showMenu.value = true
  })
}

function getMenuItems(): MenuProps['items'] {
  return buildFileContextMenuItems(targetEntry.value, t)
}

async function handleSelect(key: string) {
  showMenu.value = false
  const entry = targetEntry.value
  if (!entry) return

  switch (key) {
    case 'open':
      filesStore.navigateTo(entry.path)
      break
    case 'edit':
      try { await filesStore.openEditor(entry.path) } catch { message.error(t('files.backendError')) }
      break
    case 'preview':
      try { await filesStore.openPreview(entry) } catch { message.error(t('files.backendError')) }
      break
    case 'download':
      try { await downloadFile(entry.path, entry.name) } catch (err) { message.error(err instanceof Error ? err.message : t('files.backendError')) }
      break
    case 'restoreDefault':
      Modal.confirm({
        title: t('files.confirmRestore', { name: entry.name }),
        okText: t('common.ok'),
        cancelText: t('common.cancel'),
        onOk: async () => {
          try {
            await filesStore.restoreFile(entry.path)
            message.success(t('files.restored'))
          } catch {
            message.error(t('files.backendError'))
          }
        },
      })
      break
    case 'copyPath': {
      const ok = await copyToClipboard(entry.path)
      if (ok) {
        message.success(t('files.pathCopied'))
      } else {
        message.error(t('files.pathCopied') + ' ✗')
      }
      break
    }
  }
}

function handleClickOutside() {
  showMenu.value = false
}

function handleMenuClick(info: { key: string | number }) {
  handleSelect(String(info.key))
}

defineExpose({ show })
</script>

<template>
  <Dropdown
    :open="showMenu"
    :trigger="['click']"
    :menu="{ items: getMenuItems(), onClick: handleMenuClick }"
    placement="bottomLeft"
    @open-change="open => { if (!open) handleClickOutside() }"
  >
    <button class="context-menu-anchor" :style="{ left: `${menuX}px`, top: `${menuY}px` }" aria-hidden="true" tabindex="-1" />
  </Dropdown>
</template>

<style scoped>
.context-menu-anchor {
  position: fixed;
  z-index: 1000;
  width: 1px;
  height: 1px;
  padding: 0;
  pointer-events: none;
  background: transparent;
  border: 0;
}
</style>
