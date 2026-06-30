import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const definitions = readFileSync(
  new URL('../src/components/solonclaw/settings/platformDefinitions.ts', import.meta.url),
  'utf8',
)
const settings = readFileSync(
  new URL('../src/components/solonclaw/settings/PlatformSettings.vue', import.meta.url),
  'utf8',
)
const api = readFileSync(new URL('../src/api/solonclaw/config.ts', import.meta.url), 'utf8')
const store = readFileSync(new URL('../src/stores/solonclaw/settings.ts', import.meta.url), 'utf8')

assert.ok(
  definitions.includes('normalizePlatformSettingsItems'),
  'platform definitions should normalize backend platform catalog into renderable items',
)
assert.ok(
  definitions.includes('PLATFORM_ICON_SVG_BY_KEY'),
  'platform definitions should keep SVG rendering behind a local icon-key mapping',
)
assert.ok(
  !settings.includes('PLATFORM_SETTINGS_ITEMS'),
  'settings page should not render directly from a hard-coded platform item array',
)
assert.ok(
  settings.includes('platformSettingsItems'),
  'settings page should render from computed platform metadata items',
)
assert.ok(
  api.includes('platform_catalog'),
  'config API should read backend platform catalog metadata',
)
assert.ok(
  api.includes('platformCatalog'),
  'config API should expose normalized platform catalog metadata',
)
assert.ok(
  store.includes('platformCatalog'),
  'settings store should persist platform catalog metadata for the settings page',
)
