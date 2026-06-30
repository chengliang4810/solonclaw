import assert from 'node:assert/strict'
import { buildFileContextMenuItems } from '../src/shared/fileContextMenu.ts'
import type { FileEntry } from '../src/api/solonclaw/files.ts'

function entry(name: string, isDir = false): FileEntry {
  return {
    name,
    path: name,
    isDir,
    size: 0,
    modTime: '',
  }
}

function keysFor(file: FileEntry): string[] {
  return buildFileContextMenuItems(file, key => key).map(item => item.key)
}

assert.deepEqual(keysFor(entry('src', true)), ['open', 'd1', 'copyPath'])
assert.deepEqual(keysFor(entry('README.md')), ['edit', 'preview', 'download', 'restoreDefault', 'd1', 'copyPath'])
assert.deepEqual(keysFor(entry('diagram.png')), ['preview', 'download', 'restoreDefault', 'd1', 'copyPath'])
assert.deepEqual(keysFor(entry('archive.zip')), ['download', 'restoreDefault', 'd1', 'copyPath'])
