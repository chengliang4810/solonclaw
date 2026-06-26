<script setup lang="ts">
import { ref } from 'vue'
import { Modal, Button, UploadDragger, Space, message } from 'antdv-next'
import type { UploadFile } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useFilesStore } from '@/stores/solonclaw/files'

const { t } = useI18n()
const filesStore = useFilesStore()

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'update:open', value: boolean): void }>()

const uploading = ref(false)
const fileList = ref<File[]>([])
const uploadFileList = ref<UploadFile[]>([])

function handleFileChange(data: { fileList: UploadFile[] }) {
  uploadFileList.value = data.fileList
  const files: File[] = []
  for (const item of data.fileList) {
    const raw = item.originFileObj
    if (raw instanceof File) {
      files.push(raw)
    }
  }
  fileList.value = files
}

function beforeUpload() {
  return false
}

async function handleUpload() {
  if (fileList.value.length === 0) return
  uploading.value = true
  try {
    await filesStore.uploadFiles(fileList.value)
    message.success(t('files.uploadSuccess', { count: fileList.value.length }))
    fileList.value = []
    uploadFileList.value = []
    emit('update:open', false)
  } catch (err: any) {
    message.error(err.message || t('files.uploadFailed'))
  } finally {
    uploading.value = false
  }
}

function handleClose() {
  fileList.value = []
  uploadFileList.value = []
  emit('update:open', false)
}
</script>

<template>
  <Modal :open="props.open" :title="t('files.upload')" @update:open="value => { if (!value) handleClose() }" style="width: 500px;">
    <UploadDragger
      v-model:file-list="uploadFileList"
      multiple
      directory
      :before-upload="beforeUpload"
      @change="handleFileChange"
    >
      <div class="upload-dragger">
        <p>{{ t('files.dragDropHint') }}</p>
      </div>
    </UploadDragger>
    <template #footer>
      <Space>
        <Button @click="handleClose">{{ t('common.cancel') }}</Button>
        <Button type="primary" :loading="uploading" :disabled="fileList.length === 0" @click="handleUpload">
          {{ t('files.upload') }} ({{ fileList.length }})
        </Button>
      </Space>
    </template>
  </Modal>
</template>

<style scoped>
.upload-dragger {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  text-align: center;
  cursor: pointer;
}

.upload-dragger p {
  margin-top: 12px;
  opacity: 0.6;
  font-size: 14px;
}
</style>
