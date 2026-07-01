import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')

assert.ok(
  /function\s+syncAppRuntime/.test(app),
  'App.vue should centralize runtime polling startup so route changes can reuse it',
)
assert.ok(
  /watch\(\s*isLoginPage\s*,\s*syncAppRuntime/.test(app),
  'App.vue should watch login-page state so login -> app starts health polling without remounting',
)
assert.ok(
  app.includes('appStore.startHealthPolling()'),
  'App.vue should start health polling after entering the authenticated dashboard',
)
assert.ok(
  app.includes('appStore.stopHealthPolling()'),
  'App.vue should stop health polling when the app returns to the login page or unmounts',
)
