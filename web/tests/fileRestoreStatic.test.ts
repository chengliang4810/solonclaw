import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const filesApi = readFileSync(new URL('../src/api/solonclaw/files.ts', import.meta.url), 'utf8')
const filesStore = readFileSync(new URL('../src/stores/solonclaw/files.ts', import.meta.url), 'utf8')
const fileEditor = readFileSync(new URL('../src/components/solonclaw/files/FileEditor.vue', import.meta.url), 'utf8')

assert.ok(filesApi.includes('/api/workspace/files/${encodeURIComponent(key)}/restore'), 'workspace restore endpoint should be wrapped')
assert.ok(filesApi.includes('restoreFile'), 'restore wrapper should be exported')
assert.ok(filesStore.includes('restoreEditor'), 'files store should restore the active editor file')
assert.ok(fileEditor.includes('handleRestore'), 'file editor should expose restore action')
assert.ok(fileEditor.includes("t('files.restoreConfirm')"), 'restore action should require confirmation')
assert.ok(fileEditor.includes('syncEditorValue'), 'editor should refresh displayed content after restore')
