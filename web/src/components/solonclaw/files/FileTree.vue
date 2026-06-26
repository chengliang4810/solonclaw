<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Tree } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useFilesStore } from '@/stores/solonclaw/files'
import * as filesApi from '@/api/solonclaw/files'
import type { TreeDataNode } from 'antdv-next'
import type { Key } from '@v-c/util/dist/type'

const { t } = useI18n()
const filesStore = useFilesStore()

const treeData = ref<TreeDataNode[]>([])
const selectedKeys = ref<Key[]>([])

async function loadChildren(path: string): Promise<TreeDataNode[]> {
  try {
    const result = await filesApi.listFiles(path)
    return result.entries
      .filter(e => e.isDir)
      .sort((a, b) => a.name.localeCompare(b.name))
      .map(e => ({
        key: e.path,
        title: e.name,
        isLeaf: false,
      }))
  } catch {
    return []
  }
}

async function handleLoad(node: TreeDataNode): Promise<void> {
  node.children = await loadChildren(node.key as string)
}

function handleSelect(keys: Key[]) {
  if (keys.length > 0) {
    selectedKeys.value = keys
    filesStore.navigateTo(String(keys[0]))
  }
}

function handleRootClick() {
  selectedKeys.value = []
  filesStore.navigateTo('')
}

onMounted(async () => {
  treeData.value = await loadChildren('')
})
</script>

<template>
  <div class="file-tree">
    <div class="tree-header" @click="handleRootClick">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
        <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
        <polyline points="9 22 9 12 15 12 15 22" />
      </svg>
      <span>{{ t('files.breadcrumbRoot') }}</span>
    </div>
    <Tree
      :tree-data="treeData"
      :selected-keys="selectedKeys"
      block-node
      :load-data="handleLoad"
      @update:selected-keys="handleSelect"
    >
    </Tree>
    <div v-if="treeData.length === 0" class="tree-empty">{{ t('files.emptyTree') }}</div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.file-tree {
  padding: 8px;
}

.tree-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  cursor: pointer;
  border-radius: $radius-sm;
  font-size: 13px;
  font-weight: 500;
  color: $text-primary;

  &:hover {
    background-color: rgba(var(--accent-primary-rgb), 0.06);
  }
}

.tree-empty {
  padding: 10px 12px;
  color: $text-muted;
  font-size: 12px;
  line-height: 1.5;
}
</style>
