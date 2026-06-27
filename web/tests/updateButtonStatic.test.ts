import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const store = readFileSync(new URL('../src/stores/solonclaw/app.ts', import.meta.url), 'utf8')

assert.ok(store.includes('updateAvailable.value = false'), 'dashboard should hide the update button when no update API exists')
assert.ok(!store.includes('updateAvailable.value = !!res.webui_update_available'), 'status update_available must not expose a broken update button')
