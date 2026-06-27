<script setup lang="ts">
import { Button, Space, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useFilesStore } from '@/stores/solonclaw/files'

const { t } = useI18n()
const filesStore = useFilesStore()

async function handleRefresh() {
  try {
    await filesStore.fetchEntries()
  } catch {
    message.error(t('files.backendError'))
  }
}
</script>

<template>
  <div class="file-toolbar">
    <Space>
      <Button size="small" @click="handleRefresh">
        {{ t('files.refresh') }}
      </Button>
    </Space>
  </div>
</template>

<style scoped lang="scss">
.file-toolbar {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-color);
}
</style>
