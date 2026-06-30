import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { DISPLAY_THEME_OPTIONS, translateDisplayThemeOptions } from '../src/shared/displayThemeOptions.ts'

const displaySettings = readFileSync(
  new URL('../src/components/solonclaw/settings/DisplaySettings.vue', import.meta.url),
  'utf8',
)

assert.deepEqual(
  DISPLAY_THEME_OPTIONS.map(item => item.value),
  ['light', 'dark', 'system'],
  'Display theme options should keep the existing light, dark, and system modes',
)

assert.deepEqual(
  translateDisplayThemeOptions(key => `label:${key}`).map(item => item.label),
  [
    'label:settings.display.themeLight',
    'label:settings.display.themeDark',
    'label:settings.display.themeSystem',
  ],
  'Display theme options should translate existing locale keys',
)

assert.ok(
  displaySettings.includes("from '@/shared/displayThemeOptions'"),
  'DisplaySettings should reuse shared display theme metadata',
)
assert.ok(
  displaySettings.includes('translateDisplayThemeOptions(t)'),
  'DisplaySettings should translate theme options through shared metadata',
)
assert.ok(
  displaySettings.includes('isDisplayThemeMode(val)'),
  'DisplaySettings should validate theme values through shared metadata',
)
assert.ok(
  !displaySettings.includes('const themeOptions = ['),
  'DisplaySettings should not inline theme option metadata',
)
