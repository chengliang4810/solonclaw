import assert from 'node:assert/strict'
import { formatFileSize } from '../src/shared/fileSizeFormat.ts'

assert.equal(formatFileSize(undefined), '-')
assert.equal(formatFileSize(0), '-')
assert.equal(formatFileSize(0, '0 B'), '0 B')
assert.equal(formatFileSize(0, '—'), '—')
assert.equal(formatFileSize(12), '12 B')
assert.equal(formatFileSize(1536), '1.5 KB')
assert.equal(formatFileSize(1024 * 1024), '1.0 MB')
assert.equal(formatFileSize(3 * 1024 * 1024 * 1024), '3.0 GB')
