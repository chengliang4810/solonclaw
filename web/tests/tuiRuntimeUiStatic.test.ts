import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'

const apiFile = new URL('../src/api/solonclaw/tuiRuntime.ts', import.meta.url)
const viewFile = new URL('../src/views/solonclaw/TuiRuntimeView.vue', import.meta.url)
const systemNavFile = new URL('../src/components/layout/SystemNavItems.vue', import.meta.url)
const router = readFileSync(new URL('../src/router/index.ts', import.meta.url), 'utf8')
const sidebar = readFileSync(new URL('../src/components/layout/AppSidebar.vue', import.meta.url), 'utf8')

assert.ok(existsSync(apiFile), 'TUI runtime RPC should have a Web API wrapper')
assert.ok(existsSync(viewFile), 'TUI runtime RPC should have a dashboard page')
assert.ok(existsSync(systemNavFile), 'system sidebar items should stay in a focused component')

const api = readFileSync(apiFile, 'utf8')
const view = readFileSync(viewFile, 'utf8')
const systemNav = readFileSync(systemNavFile, 'utf8')

assert.ok(api.includes("'/api/tui/rpc'"), 'TUI runtime wrapper should call the backend RPC endpoint')
assert.ok(api.includes('fetchTuiRuntimeOverview'), 'TUI runtime wrapper should expose one read-only overview fetch')
assert.ok(!api.includes("'config.set'"), 'dashboard wrapper should not expose generic config writes')
assert.ok(!api.includes("'channel.save'"), 'dashboard wrapper should not expose channel writes')
assert.ok(!api.includes("'model.save_key'"), 'dashboard wrapper should not expose secret writes')
assert.ok(router.includes('solonclaw.tuiRuntime'), 'router should expose the TUI runtime page')
assert.ok(sidebar.includes('SystemNavItems'), 'sidebar should delegate system entries to the focused component')
assert.ok(systemNav.includes('solonclaw.tuiRuntime'), 'sidebar should expose the TUI runtime page')
assert.ok(view.includes('fetchTuiRuntimeOverview'), 'TUI runtime page should render the read-only overview')
