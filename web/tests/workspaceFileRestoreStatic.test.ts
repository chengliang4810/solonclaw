import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const filesApi = readFileSync(new URL('../src/api/solonclaw/files.ts', import.meta.url), 'utf8')
const filesStore = readFileSync(new URL('../src/stores/solonclaw/files.ts', import.meta.url), 'utf8')
const contextMenu = readFileSync(new URL('../src/components/solonclaw/files/FileContextMenu.vue', import.meta.url), 'utf8')
const contextMenuActions = readFileSync(new URL('../src/shared/fileContextMenu.ts', import.meta.url), 'utf8')

assert.ok(filesApi.includes('restoreWorkspaceFile'), 'files API should expose workspace template restore')
assert.ok(
  filesApi.includes('/api/workspace/files/${encodeURIComponent(key)}/restore'),
  'files API should call the backend restore endpoint',
)

assert.ok(filesStore.includes('async function restoreFile'), 'files store should expose restoreFile')
assert.ok(filesStore.includes('filesApi.restoreFile(filePath)'), 'files store should delegate restore to API')
assert.ok(filesStore.includes('editingFile.value.originalContent'), 'restore should keep an open editor in sync')

assert.ok(contextMenuActions.includes("key: 'restoreDefault'"), 'file context menu should expose restore default action')
assert.ok(contextMenu.includes('Modal.confirm'), 'restore default should ask for confirmation')
assert.ok(contextMenu.includes("filesStore.restoreFile(entry.path)"), 'restore default should call the store action')

for (const locale of ['zh', 'en', 'ja', 'ko', 'pt', 'fr', 'de', 'es']) {
  const content = readFileSync(new URL(`../src/i18n/locales/${locale}.ts`, import.meta.url), 'utf8')
  for (const key of ['restoreDefault', 'confirmRestore', 'restored']) {
    assert.ok(content.includes(`${key}:`), `${locale} locale should define files.${key}`)
  }
}
