import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const curatorApi = readFileSync(new URL('../src/api/solonclaw/curator.ts', import.meta.url), 'utf8')
const skillsView = readFileSync(new URL('../src/views/solonclaw/SkillsView.vue', import.meta.url), 'utf8')

assert.ok(curatorApi.includes('/api/curator?limit='), 'curator reports endpoint should be wrapped')
assert.ok(curatorApi.includes('fetchCuratorReport'), 'curator report detail wrapper should be exported')
assert.ok(skillsView.includes('fetchCuratorReports'), 'Skills view should load curator reports')
assert.ok(skillsView.includes('fetchCuratorReport'), 'Skills view should load curator report details')
assert.ok(skillsView.includes('curatorReports = ref'), 'Skills view should keep curator reports')
assert.ok(skillsView.includes("t('skills.curatorReports')"), 'Skills view should render curator reports section')
