import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/download.ts', import.meta.url), 'utf8')
const filesApi = readFileSync(new URL('../src/api/solonclaw/files.ts', import.meta.url), 'utf8')

assert.ok(
  api.includes('getFileDownloadUrl'),
  'downloadFile should reuse the real backend download URL helper',
)
assert.ok(
  api.includes('dashboardFetch('),
  'downloadFile should use dashboardFetch so protected downloads send dashboard auth headers',
)
assert.ok(
  api.includes("headers.set('Authorization', `Bearer ${apiKey}`)"),
  'downloadFile should attach the dashboard token as a Bearer header',
)
assert.ok(
  api.includes('res.blob()'),
  'downloadFile should preserve backend bytes instead of forcing text content',
)
assert.ok(
  api.includes('URL.createObjectURL(blob)') && api.includes('URL.revokeObjectURL(url)'),
  'downloadFile should trigger browser download from an authenticated blob URL',
)
assert.ok(
  !api.includes('readFile('),
  'downloadFile should not fetch fixed workspace text content for downloads',
)
assert.ok(
  !api.includes('text/plain;charset=utf-8'),
  'downloadFile should not force all downloads to UTF-8 text blobs',
)
assert.ok(
  !api.includes('a.href = getDownloadUrl'),
  'downloadFile should not navigate a naked download URL without Authorization headers',
)
assert.ok(
  !filesApi.includes("params.set('token'") && !filesApi.includes('getApiKey'),
  'workspace download URLs should not leak dashboard tokens in query strings',
)
