import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/download.ts', import.meta.url), 'utf8')

assert.ok(
  api.includes('getFileDownloadUrl'),
  'downloadFile should reuse the real backend download URL helper',
)
assert.ok(
  !api.includes('readFile('),
  'downloadFile should not fetch fixed workspace text content for downloads',
)
assert.ok(
  !api.includes('text/plain;charset=utf-8'),
  'downloadFile should not force all downloads to UTF-8 text blobs',
)
