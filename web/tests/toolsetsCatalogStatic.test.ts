import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const skillsApi = readFileSync(new URL('../src/api/solonclaw/skills.ts', import.meta.url), 'utf8')
const skillsView = readFileSync(new URL('../src/views/solonclaw/SkillsView.vue', import.meta.url), 'utf8')

assert.ok(skillsApi.includes('/api/tools/toolsets'), 'tools toolsets endpoint should be wrapped')
assert.ok(skillsApi.includes('fetchToolsets'), 'toolsets wrapper should be exported')
assert.ok(skillsView.includes('fetchToolsets'), 'Skills view should load toolsets')
assert.ok(skillsView.includes('toolsets = ref<ToolsetInfo[]>([])'), 'Skills view should keep toolsets')
assert.ok(skillsView.includes("t('skills.toolsetsTitle')"), 'Skills view should render a toolsets section')
