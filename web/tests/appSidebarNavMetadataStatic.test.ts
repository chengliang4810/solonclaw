import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const sidebarFile = new URL('../src/components/layout/AppSidebar.vue', import.meta.url)
const sidebarStyleFile = new URL('../src/components/layout/AppSidebar.scss', import.meta.url)
const sidebarNavFile = new URL('../src/shared/sidebarNav.ts', import.meta.url)

const sidebar = readFileSync(sidebarFile, 'utf8')
const sidebarStyle = readFileSync(sidebarStyleFile, 'utf8')
const sidebarNav = readFileSync(sidebarNavFile, 'utf8')

assert.ok(sidebarNav.includes('PRIMARY_NAV_ITEMS'), 'sidebar nav metadata should define primary entries')
assert.ok(sidebarNav.includes('MONITORING_NAV_ITEMS'), 'sidebar nav metadata should define monitoring entries')
assert.ok(sidebarNav.includes('PERSONA_NAV_ITEMS'), 'sidebar nav metadata should define persona entries')
assert.ok(sidebarNav.includes("key: 'solonclaw.files'"), 'sidebar nav metadata should expose workspace files')
assert.ok(sidebarNav.includes("labelKey: 'sidebar.files'"), 'workspace files nav entry should reuse the localized sidebar label')
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
assert.ok(
  sidebar.includes('ref="sidebarNavRef"'),
  'sidebar nav should keep a template ref so active items can be scrolled into view',
)
assert.ok(
  sidebar.includes('scrollIntoView({ block: "nearest" })'),
  'sidebar should scroll the active nav item into view instead of leaving bottom routes hidden behind the footer area',
)
assert.ok(
  sidebarStyle.includes('scroll-margin-block'),
  'sidebar nav items should keep scroll margin so active bottom routes do not touch the fixed footer',
)
assert.ok(
  sidebarStyle.includes('padding-bottom'),
  'sidebar nav should keep bottom padding so the last active item can scroll above the footer',
)
assert.ok(
  sidebar.includes('watch(selectedKey'),
  'sidebar should rerun active-item scrolling when the current route changes',
)
assert.ok(
  sidebar.includes("flush: 'post'"),
  'sidebar active-item scrolling should run after Vue applies route and footer updates',
)
assert.ok(
  sidebar.includes('appStore.serverVersion'),
  'sidebar should rerun active-item scrolling when footer metadata changes height',
)
