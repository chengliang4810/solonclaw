import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/runs.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/RunsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes('commands: RunControlCommand[]'), 'run detail type should include control commands from backend detail')
assert.ok(view.includes('recoveries.value = detail.recoveries || []'), 'runs view should read recoveries from run detail')
assert.ok(view.includes('commands.value = detail.commands || []'), 'runs view should read commands from run detail')
assert.ok(view.includes("t('runs.recoveries')"), 'runs view should render recoveries')
assert.ok(view.includes("t('runs.commands')"), 'runs view should render control commands')
assert.ok(zh.includes("recoveries: '恢复记录'"), 'Chinese locale should include recoveries label')
assert.ok(en.includes("recoveries: 'Recoveries'"), 'English locale should include recoveries label')
