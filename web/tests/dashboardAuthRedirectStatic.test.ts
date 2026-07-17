import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const client = readFileSync(new URL('../src/api/client.ts', import.meta.url), 'utf8')
const authError = readFileSync(new URL('../src/api/dashboardAuthError.ts', import.meta.url), 'utf8')
const chatApi = readFileSync(new URL('../src/api/solonclaw/chat.ts', import.meta.url), 'utf8')
const loginView = readFileSync(new URL('../src/views/LoginView.vue', import.meta.url), 'utf8')

assert.ok(
  authError.includes('Forbidden dashboard request origin'),
  'dashboard origin rejection detail should be recognized explicitly',
)
assert.ok(
  client.includes('export async function dashboardFetch'),
  'shared API client should expose dashboardFetch for direct fetch callers',
)
assert.ok(
  client.includes('handleDashboardAuthFailure(res.status, text)'),
  'dashboardFetch should route 401 and dashboard origin 403 failures through shared login redirect handling',
)
assert.ok(
  client.includes('const redirect = currentRoute.fullPath'),
  'auth failure should preserve the current dashboard target before redirecting to login',
)
assert.ok(
  client.includes("redirect.startsWith('/') && !redirect.startsWith('//') ? { redirect } : undefined"),
  'auth failure should only pass an internal redirect target to login',
)
assert.ok(
  chatApi.includes("dashboardFetch(`${getBaseUrlValue()}${withProfile('/api/chat/uploads', profile)}`"),
  'chat uploads should use dashboardFetch so auth failures redirect to login',
)
assert.ok(
  chatApi.includes('dashboardFetch(`${getBaseUrlValue()}${eventsPath}`'),
  'chat event streams should use dashboardFetch so auth failures redirect to login',
)
assert.ok(
  loginView.includes('dashboardFetch(`${getBaseUrlValue()}/api/sessions`'),
  'login validation should use dashboardFetch so stale stored tokens are cleared consistently',
)
