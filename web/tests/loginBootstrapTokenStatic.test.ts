import { readFileSync } from 'node:fs'
import { strict as assert } from 'node:assert'

const loginView = readFileSync(new URL('../src/views/LoginView.vue', import.meta.url), 'utf8')

assert.ok(
  loginView.includes('/api/workspace-config/bootstrap-dashboard-token'),
  'login page should try the localhost bootstrap endpoint when token validation returns 401',
)

assert.ok(
  /if \(!res\.ok\)[\s\S]*tryBootstrapDashboardToken\(key\)[\s\S]*setApiKey\(key\)[\s\S]*router\.replace\(loginTarget\(\)\)/.test(loginView),
  'successful bootstrap should store the new token and enter the originally requested dashboard page',
)
