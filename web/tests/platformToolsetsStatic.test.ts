import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const configApi = readFileSync(new URL('../src/api/solonclaw/config.ts', import.meta.url), 'utf8')
const platformSettings = readFileSync(new URL('../src/components/solonclaw/settings/PlatformSettings.vue', import.meta.url), 'utf8')

assert.ok(configApi.includes('/api/tools/platform-toolsets'), 'platform toolsets endpoint should be wrapped')
assert.ok(configApi.includes('fetchPlatformToolsets'), 'platform toolsets wrapper should be exported')
assert.ok(configApi.includes('updatePlatformToolsets'), 'platform toolsets update wrapper should be exported')
assert.ok(configApi.includes("method: 'PUT'"), 'platform toolsets update should use PUT')
assert.ok(platformSettings.includes('fetchPlatformToolsets'), 'Platform settings should load platform toolsets')
assert.ok(platformSettings.includes('updatePlatformToolsets'), 'Platform settings should save platform toolsets')
assert.ok(platformSettings.includes('platformToolsets = reactive'), 'Platform settings should keep platform toolsets')
assert.ok(platformSettings.includes('platformToolsetDrafts = reactive'), 'Platform settings should keep editable drafts')
assert.ok(platformSettings.includes("t('platform.toolsets')"), 'Platform settings should render platform toolsets')
assert.ok(platformSettings.includes('savePlatformToolsets'), 'Platform settings should render save action')
