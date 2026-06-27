import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const loginView = readFileSync(new URL('../src/views/LoginView.vue', import.meta.url), 'utf8')

assert.ok(
  loginView.includes('getBaseUrlValue, handleDashboardAuthFailure, setApiKey, hasApiKey'),
  'login view should use the shared client auth helpers',
)
assert.ok(
  loginView.includes('`${getBaseUrlValue()}/api/sessions`'),
  'login validation should respect the configured dashboard API base URL',
)
assert.ok(
  loginView.includes('handleDashboardAuthFailure(res.status, body)'),
  'login validation should route dashboard auth failures through the shared redirect handler',
)
assert.ok(
  !loginView.includes('isDashboardOriginRejected'),
  'login view should not duplicate dashboard origin rejection handling',
)
