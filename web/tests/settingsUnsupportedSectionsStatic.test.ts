import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const settingsView = readFileSync(new URL('../src/views/solonclaw/SettingsView.vue', import.meta.url), 'utf8')
const sessionSettings = readFileSync(new URL('../src/components/solonclaw/settings/SessionSettings.vue', import.meta.url), 'utf8')
const displaySettings = readFileSync(new URL('../src/components/solonclaw/settings/DisplaySettings.vue', import.meta.url), 'utf8')
const agentSettings = readFileSync(new URL('../src/components/solonclaw/settings/AgentSettings.vue', import.meta.url), 'utf8')
const settingsStore = readFileSync(new URL('../src/stores/solonclaw/settings.ts', import.meta.url), 'utf8')
const configApi = readFileSync(new URL('../src/api/solonclaw/config.ts', import.meta.url), 'utf8')

assert.ok(!settingsView.includes('MemorySettings'), 'settings page should not mount unsupported memory saves')
assert.ok(!settingsView.includes('PrivacySettings'), 'settings page should not mount unsupported privacy saves')
assert.ok(!sessionSettings.includes("saveSection('session_reset'"), 'session settings should not fake-save unsupported reset options')
assert.ok(sessionSettings.includes('sessionBrowserPrefsStore.setHumanOnly'), 'session settings should keep the real local browser preference')
assert.ok(!settingsStore.includes('MemoryConfig'), 'settings store should not expose unsupported memory config')
assert.ok(!settingsStore.includes('PrivacyConfig'), 'settings store should not expose unsupported privacy config')
for (const section of ['telegram', 'discord', 'slack', 'whatsapp', 'matrix']) {
  assert.ok(!settingsStore.includes(section), `settings store should not expose unsupported ${section} config`)
}
assert.ok(!configApi.includes('memory_enabled'), 'config API should not synthesize unsupported memory config')
assert.ok(!configApi.includes('redact_pii'), 'config API should not synthesize unsupported privacy config')
assert.ok(!displaySettings.includes('compact'), 'display settings should not expose unsupported compact saves')
assert.ok(!displaySettings.includes('inline_diffs'), 'display settings should not expose unsupported inline diff saves')
assert.ok(!displaySettings.includes('bell_on_complete'), 'display settings should not expose unsupported completion bell saves')
assert.ok(!displaySettings.includes('busy_input_mode'), 'display settings should not expose unsupported busy input mode saves')
assert.ok(!displaySettings.includes('save({ skin'), 'theme changes should stay local and not fake-save to backend')
assert.ok(!agentSettings.includes('gateway_timeout'), 'agent settings should not expose unmapped gateway timeout saves')
assert.ok(!agentSettings.includes('restart_drain_timeout'), 'agent settings should not expose unmapped restart drain saves')
assert.ok(!agentSettings.includes('tool_use_enforcement'), 'agent settings should not expose unsupported tool enforcement saves')
