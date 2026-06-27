import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const toolbar = readFileSync(new URL('../src/components/solonclaw/files/FileToolbar.vue', import.meta.url), 'utf8')
const contextMenu = readFileSync(new URL('../src/components/solonclaw/files/FileContextMenu.vue', import.meta.url), 'utf8')
const filesView = readFileSync(new URL('../src/views/solonclaw/FilesView.vue', import.meta.url), 'utf8')
const filesStore = readFileSync(new URL('../src/stores/solonclaw/files.ts', import.meta.url), 'utf8')
const filesApi = readFileSync(new URL('../src/api/solonclaw/files.ts', import.meta.url), 'utf8')

for (const token of ['showNewFile', 'showNewFolder', 'showUpload', "t('files.newFile')", "t('files.newFolder')", "t('files.upload')"]) {
  assert.ok(!toolbar.includes(token), `file toolbar should not expose unsupported action ${token}`)
}

for (const token of ["key: 'rename'", "key: 'delete'", "t('files.rename')", "t('files.delete')", 'deleteEntry']) {
  assert.ok(!contextMenu.includes(token), `file context menu should not expose unsupported action ${token}`)
}

assert.ok(!filesView.includes('FileUploadModal'), 'files view should not mount unsupported upload modal')
assert.ok(!filesView.includes('FileRenameModal'), 'files view should not mount unsupported create/rename modal')

for (const token of ['createDir', 'createFile', 'deleteEntry', 'renameEntry', 'copyEntry', 'uploadFiles']) {
  assert.ok(!filesStore.includes(token), `files store should not expose unsupported action ${token}`)
}

for (const token of ['deleteFile', 'renameFile', 'mkDir', 'copyFile', 'uploadFiles', 'unsupported()']) {
  assert.ok(!filesApi.includes(token), `files API should not expose unsupported action ${token}`)
}
