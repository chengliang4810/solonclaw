import assert from 'node:assert/strict'
import { fileOpenMode } from '../src/shared/fileDisplay.ts'

assert.equal(fileOpenMode({ isDir: true, name: 'docs' }), 'navigate')
assert.equal(fileOpenMode({ isDir: false, name: 'README.md' }), 'preview')
assert.equal(fileOpenMode({ isDir: false, name: 'image.png' }), 'preview')
assert.equal(fileOpenMode({ isDir: false, name: 'notes.txt' }), 'edit')
assert.equal(fileOpenMode({ isDir: false, name: 'archive.zip' }), 'none')
