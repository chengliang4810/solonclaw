import assert from 'node:assert/strict'
import { formatFileSize } from '../src/shared/file-size.ts'

assert.equal(formatFileSize(0), '0 B')
assert.equal(formatFileSize(0, '-'), '-')
assert.equal(formatFileSize(512), '512 B')
assert.equal(formatFileSize(1024), '1.0 KB')
assert.equal(formatFileSize(1024 * 1024), '1.0 MB')
