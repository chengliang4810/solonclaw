import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const router = readFileSync(new URL('../src/router/index.ts', import.meta.url), 'utf8')
const sidebar = readFileSync(new URL('../src/components/layout/AppSidebar.vue', import.meta.url), 'utf8')
const switcher = readFileSync(new URL('../src/components/layout/ProfileSwitcher.vue', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/ProfilesView.vue', import.meta.url), 'utf8')
const builder = readFileSync(new URL('../src/views/solonclaw/ProfileBuilderView.vue', import.meta.url), 'utf8')
const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
const client = readFileSync(new URL('../src/api/client.ts', import.meta.url), 'utf8')
const api = readFileSync(new URL('../src/api/solonclaw/profiles.ts', import.meta.url), 'utf8')

assert.ok(router.includes("path: '/profiles'"), 'router should expose the Profiles page')
assert.ok(router.includes("path: '/profiles/new'"), 'router should expose the dedicated stepped Profile builder')
assert.ok(router.includes("name: 'solonclaw.profiles.new'"), 'Profile builder should have a stable named route')
assert.ok(sidebar.includes('<ProfileSwitcher />'), 'sidebar should render the machine-level Profile switcher')
assert.ok(
  app.includes(':key="profilesStore.managedProfileName"'),
  'switching the managed Profile should remount the routed page tree and clear stale local state',
)
assert.ok(switcher.includes("query.profile"), 'Profile selection should remain deep-linkable through ?profile=')
assert.ok(client.includes('profiledApiPath'), 'the shared API client should inject the management Profile')
assert.ok(api.includes("'/api/profiles/active'"), 'Profiles API should distinguish sticky active Profile')
for (const action of [
  'createProfile',
  'renameProfile',
  'deleteProfile',
  'importProfile',
  'exportProfile',
  'updateProfileDescription',
  'describeProfileAutomatically',
  'fetchProfileSoul',
  'updateProfileSoul',
  'updateProfileModel',
  'createProfileAlias',
  'removeProfileAlias',
  'installProfileDistribution',
  'updateProfileDistribution',
  'startProfileGateway',
  'stopProfileGateway',
  'restartProfileGateway',
  'searchProfileHubSkills',
]) {
  assert.ok(api.includes(`function ${action}`), `Profiles API should expose ${action}`)
}
for (const field of ['clone_from_default', 'provider', 'model', 'mcp_servers', 'keep_skills', 'hub_skills']) {
  assert.ok(api.includes(`${field}?:`), `full Profile create contract should include ${field}`)
}
assert.ok(api.includes('`/api/skills/hub/search?${params.toString()}`'), 'Profile builder should use the Skills Hub search contract')
assert.ok(api.includes('force_config: forceConfig'), 'distribution updates should expose force_config semantics')
assert.ok(api.includes('JSON.stringify(options)'), 'gateway start and restart should forward args and force options')
for (const routeName of [
  'solonclaw.settings',
  'solonclaw.runs',
  'solonclaw.persona.journal',
  'solonclaw.skills',
  'solonclaw.mcp',
  'solonclaw.channels',
  'solonclaw.gateways',
]) {
  assert.ok(view.includes(routeName), `Profiles page should route ${routeName} through the selected Profile`)
}

for (const action of [
  "openEditor(profile, 'model')",
  "openEditor(profile, 'description')",
  "openEditor(profile, 'soul')",
  "openEditor(profile, 'alias')",
  'openUpdateDistribution(profile)',
  "requestGateway(profile, 'start')",
  "requestGateway(profile, 'stop')",
  "requestGateway(profile, 'restart')",
]) {
  assert.ok(view.includes(action), `Profiles list should expose ${action}`)
}
assert.ok(view.includes("name: 'solonclaw.profiles.new'"), 'Build should navigate to the dedicated builder')
assert.ok(view.includes('clone_from: cloneFrom.value || null'), 'quick create should preserve explicit clone-source semantics')
assert.ok(view.includes('clone_all: !!cloneFrom.value && cloneAll.value'), 'clone_all should only apply while cloning')
assert.ok(view.includes('no_skills: !cloneFrom.value && noSkills.value'), 'no_skills should only apply to a fresh Profile')
assert.ok(view.includes('watch(cloneFrom'), 'switching to a fresh Profile should clear stale clone_all state')
assert.ok(view.includes('if (!name || name === renameFrom.value)'), 'an unchanged rename should close without an invalid-name error')
assert.ok(view.includes('profile.credentials_exists'), 'Profile cards should surface the configured env badge')
assert.ok(view.includes('function isCurrentEditorRequest'), 'Profile editors should reject stale async responses')
assert.ok(
  (view.match(/const requestId = editorRequestId/g) || []).length >= 4,
  'save, describe, alias removal, and editor loading should pin the Profile editor request',
)
assert.ok(
  view.includes("isCurrentEditorRequest(requestId, profileName, 'description')"),
  'auto-description should not overwrite a different Profile editor after an async response',
)

for (const step of ['identity', 'model', 'skills', 'mcp', 'review']) {
  assert.ok(builder.includes(`'${step}'`), `Profile builder should include the ${step} step`)
}
for (const field of ['provider:', 'model:', 'mcp_servers:', 'keep_skills:', 'hub_skills:']) {
  assert.ok(builder.includes(field), `Profile builder should submit ${field}`)
}
assert.ok(builder.includes('clone_from: null'), 'full builder should create a fresh composable Profile')
assert.ok(builder.includes("searchProfileHubSkills(query, 'all', 20)"), 'Hub search should preserve source=all and limit=20')
assert.ok(builder.includes("args.split(/\\s+/)"), 'MCP stdio args should use the reference space-separated parameter semantics')
assert.ok(builder.includes('profilesStore.createProfile({'), 'the final Review action should use one Profile create request')
for (const earlyWrite of ['updateProfileModel(', 'saveMcpServer(', 'toggleSkill(']) {
  assert.equal(builder.includes(earlyWrite), false, `builder should not write early through ${earlyWrite}`)
}
