import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const systemNavFile = new URL('../src/components/layout/SystemNavItems.vue', import.meta.url)
const sidebarStyleFile = new URL('../src/components/layout/AppSidebar.scss', import.meta.url)
const sidebarNavFile = new URL('../src/shared/sidebarNav.ts', import.meta.url)
const zhFile = new URL('../src/i18n/locales/zh.ts', import.meta.url)
const enFile = new URL('../src/i18n/locales/en.ts', import.meta.url)

const systemNav = readFileSync(systemNavFile, 'utf8')
const sidebarStyle = readFileSync(sidebarStyleFile, 'utf8')
const sidebarNav = readFileSync(sidebarNavFile, 'utf8')
const zh = readFileSync(zhFile, 'utf8')
const en = readFileSync(enFile, 'utf8')

assert.ok(
  sidebarNav.includes('SYSTEM_NAV_ITEMS'),
  'system navigation should keep route, label, and icon metadata in one data catalog',
)
assert.ok(
  systemNav.includes("import { SYSTEM_NAV_ITEMS } from '@/shared/sidebarNav'"),
  'system navigation should import route metadata from the shared sidebar catalog',
)
assert.ok(
  systemNav.includes('v-for="item in SYSTEM_NAV_ITEMS"'),
  'system navigation should render entries from the metadata catalog',
)
assert.ok(!systemNav.includes("key: 'solonclaw.settings'"), 'system navigation component should not inline route keys')
assert.ok(sidebarNav.includes("key: 'solonclaw.settings'"), 'shared sidebar catalog should include settings route')
assert.ok(sidebarNav.includes("key: 'solonclaw.tuiRuntime'"), 'shared sidebar catalog should include TUI runtime route')
assert.ok(sidebarNav.includes("key: 'solonclaw.mcp'"), 'shared sidebar catalog should include MCP route')
assert.equal(
  (systemNav.match(/class="nav-item"/g) || []).length,
  1,
  'system navigation should keep one reusable nav-item button shell',
)
assert.ok(
  sidebarStyle.includes(':deep(.nav-item)'),
  'sidebar nav item styles should reach delegated system navigation buttons',
)
assert.ok(
  !systemNav.includes('终端运行时'),
  'system navigation labels should come from locale metadata instead of hard-coded Chinese text',
)
assert.ok(zh.includes("tuiRuntime: '终端运行时'"), 'Chinese sidebar locale should include TUI runtime')
assert.ok(en.includes("tuiRuntime: 'TUI Runtime'"), 'English sidebar locale should include TUI runtime')
