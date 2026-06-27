import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const store = readFileSync(new URL('../src/stores/solonclaw/app.ts', import.meta.url), 'utf8')
const sidebar = readFileSync(new URL('../src/components/layout/AppSidebar.vue', import.meta.url), 'utf8')
const systemApi = readFileSync(new URL('../src/api/solonclaw/system.ts', import.meta.url), 'utf8')

assert.ok(!store.includes('updateAvailable'), 'dashboard store should not expose a broken update state')
assert.ok(!store.includes('doUpdate'), 'dashboard store should not expose a broken update action')
assert.ok(!store.includes('updateAvailable.value = !!res.webui_update_available'), 'status update_available must not expose a broken update button')
assert.ok(!sidebar.includes('handleUpdate'), 'sidebar should not render a broken update action')
assert.ok(!systemApi.includes('triggerUpdate'), 'system API should not expose a fake update wrapper')
