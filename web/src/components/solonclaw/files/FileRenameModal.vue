<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { Modal, Input, Button, Space, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useFilesStore } from '@/stores/solonclaw/files'
import type { FileEntry } from '@/api/solonclaw/files'

const { t } = useI18n()
const filesStore = useFilesStore()

const props = defineProps<{
  open: boolean
  mode: 'newFile' | 'newFolder' | 'rename'
  entry?: FileEntry | null
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
}>()

const inputValue = ref('')
const submitting = ref(false)

watch(() => props.open, (val) => {
  if (val) {
    if (props.mode === 'rename' && props.entry) {
      inputValue.value = props.entry.name
    } else {
      inputValue.value = ''
    }
  }
})

const title = computed(() => {
  switch (props.mode) {
    case 'newFile': return t('files.newFile')
    case 'newFolder': return t('files.newFolder')
    case 'rename': return t('files.rename')
  }
})

const placeholder = computed(() => {
  switch (props.mode) {
    case 'newFile': return t('files.newFileName')
    case 'newFolder': return t('files.newFolderName')
    case 'rename': return t('files.renameTo')
  }
})

async function handleSubmit() {
  if (!inputValue.value.trim()) return
  submitting.value = true
  try {
    switch (props.mode) {
      case 'newFile':
        await filesStore.createFile(inputValue.value.trim())
        message.success(t('files.created'))
        break
      case 'newFolder':
        await filesStore.createDir(inputValue.value.trim())
        message.success(t('files.created'))
        break
      case 'rename':
        if (props.entry) {
          await filesStore.renameEntry(props.entry, inputValue.value.trim())
          message.success(t('files.renamed'))
        }
        break
    }
    emit('update:open', false)
  } catch (err: any) {
    const msg = props.mode === 'rename' ? t('files.renameFailed') : t('files.createFailed')
    message.error(err.message || msg)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Modal :open="props.open" :title="title" @update:open="value => { if (!value) emit('update:open', false) }" style="width: 400px;">
    <Input
      v-model:value="inputValue"
      :placeholder="placeholder"
      @keydown.enter="handleSubmit"
      autofocus
    />
    <template #footer>
      <Space>
        <Button @click="emit('update:open', false)">{{ t('common.cancel') }}</Button>
        <Button type="primary" :loading="submitting" :disabled="!inputValue.trim()" @click="handleSubmit">
          {{ t('common.ok') }}
        </Button>
      </Space>
    </template>
  </Modal>
</template>
