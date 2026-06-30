import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const sidebarFile = new URL('../src/components/layout/AppSidebar.vue', import.meta.url)
const sidebarNavFile = new URL('../src/shared/sidebarNav.ts', import.meta.url)

const sidebar = readFileSync(sidebarFile, 'utf8')
const sidebarNav = readFileSync(sidebarNavFile, 'utf8')

assert.ok(sidebarNav.includes('PRIMARY_NAV_ITEMS'), 'sidebar nav metadata should define primary entries')
assert.ok(sidebarNav.includes('MONITORING_NAV_ITEMS'), 'sidebar nav metadata should define monitoring entries')
assert.ok(sidebarNav.includes('PERSONA_NAV_ITEMS'), 'sidebar nav metadata should define persona entries')
assert.ok(sidebar.includes('v-for="item in PRIMARY_NAV_ITEMS"'), 'sidebar should render primary entries from metadata')
assert.ok(sidebar.includes('v-for="item in personaItems"'), 'sidebar should render persona entries from metadata')
assert.ok(sidebar.includes('v-for="item in MONITORING_NAV_ITEMS"'), 'sidebar should render monitoring entries from metadata')
assert.ok(!sidebar.includes('const personaItems = ['), 'sidebar component should not own persona entry metadata')
assert.equal(
  (sidebar.match(/class="nav-item"/g) || []).length,
  3,
  'sidebar should keep one reusable nav-item button shell for each rendered metadata group',
)
assert.ok(sidebar.includes('class="nav-item logout-item"'), 'sidebar should keep the logout action outside nav metadata')
