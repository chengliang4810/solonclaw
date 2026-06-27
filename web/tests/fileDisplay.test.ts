import assert from 'node:assert/strict'
import { fileContextPrimaryAction, fileOpenMode, shouldShowInlineEditAction } from '../src/shared/fileDisplay.ts'

assert.equal(fileOpenMode({ isDir: true, name: 'docs' }), 'navigate')
assert.equal(fileOpenMode({ isDir: false, name: 'README.md' }), 'preview')
assert.equal(fileOpenMode({ isDir: false, name: 'image.png' }), 'preview')
assert.equal(fileOpenMode({ isDir: false, name: 'notes.txt' }), 'edit')
assert.equal(fileOpenMode({ isDir: false, name: 'archive.zip' }), 'none')

assert.equal(fileContextPrimaryAction({ isDir: true, name: 'docs' }), 'open')
assert.equal(fileContextPrimaryAction({ isDir: false, name: 'README.md' }), 'preview')
assert.equal(fileContextPrimaryAction({ isDir: false, name: 'notes.txt' }), 'edit')
assert.equal(fileContextPrimaryAction({ isDir: false, name: 'archive.zip' }), null)

assert.equal(shouldShowInlineEditAction({ isDir: false, name: 'README.md' }), false)
assert.equal(shouldShowInlineEditAction({ isDir: false, name: 'notes.txt' }), true)
assert.equal(shouldShowInlineEditAction({ isDir: true, name: 'docs' }), false)
