import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../src/views/solonclaw/SkillsView.vue', import.meta.url), 'utf8')

assert.ok(source.includes('loadError'), 'SkillsView should keep a persistent load error state')
assert.ok(source.includes("t('skills.loadFailed')"), 'SkillsView should render the localized skills load failure label')
assert.ok(source.includes('skills-load-error'), 'SkillsView should render a visible load failure block')
assert.ok(!source.includes("console.error('Failed to load skills:'"), 'SkillsView should not rely on console-only load failures')
