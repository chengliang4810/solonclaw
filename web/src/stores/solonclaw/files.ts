import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as filesApi from '@/api/solonclaw/files'
import type { FileEntry } from '@/api/solonclaw/files'
import {
  getLanguageFromPath,
  isImageFile,
  isMarkdownFile,
  isTextFile,
} from '../../shared/fileTypes.ts'

export { isImageFile, isMarkdownFile, isTextFile }

export const useFilesStore = defineStore('files', () => {
  const currentPath = ref('')
  const entries = ref<FileEntry[]>([])
  const loading = ref(false)
  const loadError = ref<string | null>(null)
  const sortBy = ref<'name' | 'size' | 'modTime'>('name')
  const sortOrder = ref<'asc' | 'desc'>('asc')

  const editingFile = ref<{
    path: string
    content: string
    originalContent: string
    language: string
  } | null>(null)

  const previewFile = ref<{
    path: string
    type: 'image' | 'markdown'
    content?: string
  } | null>(null)

  const pathSegments = computed(() => {
    if (!currentPath.value) return []
    return currentPath.value.split('/').filter(Boolean)
  })

  const sortedEntries = computed(() => {
    const copy = [...entries.value]
    copy.sort((a, b) => {
      if (a.isDir !== b.isDir) return a.isDir ? -1 : 1
      let cmp = 0
      switch (sortBy.value) {
        case 'name': cmp = a.name.localeCompare(b.name); break
        case 'size': cmp = a.size - b.size; break
        case 'modTime': cmp = a.modTime.localeCompare(b.modTime); break
      }
      return sortOrder.value === 'asc' ? cmp : -cmp
    })
    return copy
  })

  async function fetchEntries(path?: string) {
    if (path !== undefined && path !== currentPath.value) {
      // Switching directory invalidates the current preview; close it so the
      // file list becomes visible again. The editor has its own dirty-check
      // (see hasUnsavedChanges), so we leave editingFile alone here.
      previewFile.value = null
    }
    if (path !== undefined) currentPath.value = path
    loading.value = true
    loadError.value = null
    try {
      const result = await filesApi.listFiles(currentPath.value)
      entries.value = result.entries
    } catch (err) {
      console.error('Failed to fetch files:', err)
      entries.value = []
      loadError.value = err instanceof Error ? err.message : String(err || 'Failed to fetch files')
      throw err
    } finally {
      loading.value = false
    }
  }

  function navigateTo(path: string) { return fetchEntries(path) }
  function navigateUp() {
    const parts = currentPath.value.split('/').filter(Boolean)
    parts.pop()
    return fetchEntries(parts.join('/'))
  }

  async function openEditor(filePath: string) {
    const result = await filesApi.readFile(filePath)
    editingFile.value = {
      path: filePath,
      content: result.content,
      originalContent: result.content,
      language: getLanguageFromPath(filePath),
    }
  }

  async function saveEditor() {
    if (!editingFile.value) return
    await filesApi.writeFile(editingFile.value.path, editingFile.value.content)
    editingFile.value.originalContent = editingFile.value.content
  }

  async function restoreFile(filePath: string) {
    const restored = await filesApi.restoreFile(filePath)
    const content = restored.content || ''
    if (editingFile.value?.path === filePath) {
      editingFile.value.content = content
      editingFile.value.originalContent = content
    }
    await fetchEntries()
    return restored
  }

  function closeEditor() { editingFile.value = null }

  async function openPreview(entry: FileEntry) {
    if (isImageFile(entry.name)) {
      previewFile.value = { path: entry.path, type: 'image' }
    } else if (isMarkdownFile(entry.name)) {
      const result = await filesApi.readFile(entry.path)
      previewFile.value = { path: entry.path, type: 'markdown', content: result.content }
    }
  }

  function closePreview() { previewFile.value = null }

  function setSort(by: 'name' | 'size' | 'modTime') {
    if (sortBy.value === by) {
      sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
    } else {
      sortBy.value = by
      sortOrder.value = 'asc'
    }
  }

  const hasUnsavedChanges = computed(() => {
    if (!editingFile.value) return false
    return editingFile.value.content !== editingFile.value.originalContent
  })

  return {
    currentPath, entries, loading, sortBy, sortOrder,
    loadError, editingFile, previewFile,
    pathSegments, sortedEntries, hasUnsavedChanges,
    fetchEntries, navigateTo, navigateUp,
    openEditor, saveEditor, restoreFile, closeEditor,
    openPreview, closePreview,
    setSort,
  }
})
