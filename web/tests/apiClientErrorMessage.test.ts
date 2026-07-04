import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const testsDir = dirname(fileURLToPath(import.meta.url))
const tempDir = mkdtempSync(join(testsDir, '.tmp-api-client-error-'))
const originalFetch = globalThis.fetch

try {
  const clientPath = join(tempDir, 'client-under-test.ts')
  const source = readFileSync(new URL('../src/api/client.ts', import.meta.url), 'utf8')
    .replace("import router from '@/router'", "import router from './mock-router.ts'")
    .replace("import { isDashboardOriginRejected } from './dashboardAuthError.ts'", "import { isDashboardOriginRejected } from './mock-dashboard-auth-error.ts'")
    .replaceAll("from './sessionAuth.ts'", "from './mock-session-auth.ts'")

  writeFileSync(join(tempDir, 'mock-router.ts'), `
export default {
  currentRoute: { value: { name: 'home' } },
  replace() {},
}
`)
  writeFileSync(join(tempDir, 'mock-dashboard-auth-error.ts'), `
export function isDashboardOriginRejected() {
  return false
}
`)
  writeFileSync(join(tempDir, 'mock-session-auth.ts'), `
export function clearApiKey() {}
export function getApiKey() { return '' }
export function getBaseUrlValue() { return 'http://dashboard.local' }
export function hasApiKey() { return false }
export function setApiKey() {}
export function setServerUrl() {}
`)
  writeFileSync(clientPath, source)

  globalThis.fetch = async () => new Response(
    JSON.stringify({
      success: false,
      code: 'CRON_BAD_REQUEST',
      error: '请求体 JSON 解析失败：For input string: "?"',
    }),
    { status: 400, headers: { 'content-type': 'application/json' } },
  )

  const { request } = await import(pathToFileURL(clientPath).href)
  await assert.rejects(
    () => request('/api/cron/jobs', { method: 'POST' }),
    error => {
      assert.ok(error instanceof Error)
      assert.equal(error.message, '请求体 JSON 解析失败：For input string: "?"')
      assert.doesNotMatch(error.message, /^\s*\{/)
      assert.doesNotMatch(error.message, /"code":"CRON_BAD_REQUEST"/)
      return true
    },
  )
} finally {
  globalThis.fetch = originalFetch
  rmSync(tempDir, { recursive: true, force: true })
}
