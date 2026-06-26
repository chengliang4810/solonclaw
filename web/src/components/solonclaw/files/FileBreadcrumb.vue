<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Breadcrumb, BreadcrumbItem } from 'antdv-next'
import { useFilesStore } from '@/stores/solonclaw/files'

const { t } = useI18n()
const filesStore = useFilesStore()

function handleClick(index: number) {
  if (index < 0) {
    filesStore.navigateTo('')
  } else {
    const path = filesStore.pathSegments.slice(0, index + 1).join('/')
    filesStore.navigateTo(path)
  }
}
</script>

<template>
  <div class="file-breadcrumb">
    <Breadcrumb>
      <BreadcrumbItem @click="handleClick(-1)">
        {{ t('files.breadcrumbRoot') }}
      </BreadcrumbItem>
      <BreadcrumbItem
        v-for="(segment, index) in filesStore.pathSegments"
        :key="index"
        @click="handleClick(index)"
      >
        {{ segment }}
      </BreadcrumbItem>
    </Breadcrumb>
  </div>
</template>

<style scoped lang="scss">
.file-breadcrumb {
  padding: 0 16px;
}
</style>
