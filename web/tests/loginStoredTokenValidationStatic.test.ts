import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const loginView = readFileSync(new URL('../src/views/LoginView.vue', import.meta.url), 'utf8')
const router = readFileSync(new URL('../src/router/index.ts', import.meta.url), 'utf8')

assert.ok(
  loginView.includes('async function validateExistingToken()'),
  'login page should validate a stored or injected token before leaving the login page',
)
assert.ok(
  loginView.includes('const existingKey = (urlToken || getApiKey()).trim()'),
  'login page should validate a URL token before falling back to a stale stored token',
)
assert.ok(
  loginView.includes('await validateExistingToken()'),
  'login page should run stored-token validation during setup',
)
assert.ok(
  loginView.includes('if (urlToken || hasApiKey())'),
  'login page should validate an injected URL token even when no token is stored locally',
)
assert.ok(
  /async function validateExistingToken\(\)[\s\S]*setApiKey\(existingKey\)[\s\S]*router\.replace\(loginTarget\(\)\)/.test(loginView),
  'successful injected token validation should persist the token before routing into the app',
)
assert.ok(
  !loginView.includes('if (hasApiKey()) {\n  router.replace("/chat");\n}'),
  'login page must not route to chat only because a token exists locally',
)
assert.ok(
  loginView.includes('route.redirectedFrom'),
  'login page should preserve the guarded route that sent the user to login',
)
assert.ok(
  router.includes("query: { redirect: to.fullPath }"),
  'router guard should pass direct dashboard targets to login explicitly',
)
assert.ok(
  loginView.includes("route.query.redirect"),
  'login page should restore the explicit redirect query after token validation',
)
assert.ok(
  !loginView.includes('router.replace("/chat")'),
  'login page should not hardcode chat as every successful login target',
)
assert.ok(
  loginView.includes('clearApiKey()'),
  'failed stored-token validation should clear stale local and injected tokens',
)
assert.ok(
  loginView.includes('handleDashboardAuthFailure(res.status, body)'),
  'stored-token validation should treat dashboard origin rejections as auth failures',
)
assert.ok(
  !router.includes("if (to.name === 'login' && hasApiKey())"),
  'router guard must not skip the login page only because a token exists locally',
)
