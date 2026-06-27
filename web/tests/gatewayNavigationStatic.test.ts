import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const router = readFileSync(new URL('../src/router/index.ts', import.meta.url), 'utf8')
const sidebar = readFileSync(new URL('../src/components/layout/AppSidebar.vue', import.meta.url), 'utf8')

assert.ok(router.includes("path: '/solonclaw/gateways'"), 'router should expose the gateway status page')
assert.ok(router.includes("name: 'solonclaw.gateways'"), 'gateway route should use a stable route name')
assert.ok(sidebar.includes("selectedKey === 'solonclaw.gateways'"), 'sidebar should highlight the gateway route')
assert.ok(sidebar.includes("handleNav('solonclaw.gateways')"), 'sidebar should navigate to the gateway page')
