import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as filesApi from '@/api/solonclaw/files'
import type { FileEntry } from '@/api/solonclaw/files'
import { useProfileContextGuard } from '@/composables/useProfileContextGuard'
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

  /** 清空仅属于当前 Profile 的工作区状态。 */
  function resetProfileState(): void {
    currentPath.value = ''
    entries.value = []
    loading.value = false
    loadError.value = null
    editingFile.value = null
    previewFile.value = null
  }

  const profileContext = useProfileContextGuard(resetProfileState)

  async function fetchEntries(path?: string) {
    const contextVersion = profileContext.capture()
    const nextPath = path !== undefined ? path : currentPath.value
    loading.value = true
    loadError.value = null
    try {
      const result = await filesApi.listFiles(nextPath)
      if (!profileContext.isCurrent(contextVersion)) return
      if (nextPath !== currentPath.value) {
        // Switching directory invalidates the current preview; close it so the
        // file list becomes visible again. The editor has its own dirty-check
        // (see hasUnsavedChanges), so we leave editingFile alone here.
        previewFile.value = null
      }
      currentPath.value = result.path ?? nextPath
      entries.value = result.entries
    } catch (err) {
      if (!profileContext.isCurrent(contextVersion)) return
      console.error('Failed to fetch files:', err)
      loadError.value = err instanceof Error ? err.message : String(err || 'Failed to fetch files')
      throw err
    } finally {
      if (profileContext.isCurrent(contextVersion)) loading.value = false
    }
  }

  function navigateTo(path: string) { return fetchEntries(path) }
  function navigateUp() {
    const parts = currentPath.value.split('/').filter(Boolean)
    parts.pop()
    return fetchEntries(parts.join('/'))
  }

  async function openEditor(filePath: string) {
    const contextVersion = profileContext.capture()
    const result = await filesApi.readFile(filePath)
    if (!profileContext.isCurrent(contextVersion)) return
    editingFile.value = {
      path: filePath,
      content: result.content,
      originalContent: result.content,
      language: getLanguageFromPath(filePath),
    }
  }

  async function saveEditor() {
    if (!editingFile.value) return
    const contextVersion = profileContext.capture()
    const editor = editingFile.value
    const content = editor.content
    await filesApi.writeFile(editor.path, content)
    if (!profileContext.isCurrent(contextVersion) || editingFile.value !== editor) return
    editor.originalContent = content
  }

  async function restoreFile(filePath: string) {
    const contextVersion = profileContext.capture()
    const restored = await filesApi.restoreFile(filePath)
    if (!profileContext.isCurrent(contextVersion)) return restored
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
    const contextVersion = profileContext.capture()
    if (isImageFile(entry.name)) {
      if (!profileContext.isCurrent(contextVersion)) return
      previewFile.value = { path: entry.path, type: 'image' }
    } else if (isMarkdownFile(entry.name)) {
      const result = await filesApi.readFile(entry.path)
      if (!profileContext.isCurrent(contextVersion)) return
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
