import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const router = readFileSync(new URL('../src/router/index.ts', import.meta.url), 'utf8')
const sidebar = readFileSync(new URL('../src/components/layout/AppSidebar.vue', import.meta.url), 'utf8')
const sidebarNav = readFileSync(new URL('../src/shared/sidebarNav.ts', import.meta.url), 'utf8')

assert.ok(router.includes("path: '/solonclaw/gateways'"), 'router should expose the gateway status page')
assert.ok(router.includes("name: 'solonclaw.gateways'"), 'gateway route should use a stable route name')
assert.ok(sidebarNav.includes("key: 'solonclaw.gateways'"), 'sidebar metadata should include the gateway route')
assert.ok(sidebarNav.includes("labelKey: 'sidebar.gateways'"), 'gateway route should keep its localized sidebar label')
assert.ok(
  sidebar.includes('v-for="item in MONITORING_NAV_ITEMS"'),
  'sidebar should render monitoring entries from the shared metadata catalog',
)
