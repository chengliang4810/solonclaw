import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const sessionsApi = readFileSync(new URL('../src/api/solonclaw/sessions.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/RunsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(sessionsApi.includes('fetchSessionRecap'), 'sessions API should expose recap fetch')
assert.ok(sessionsApi.includes('fetchSessionTrajectory'), 'sessions API should expose trajectory fetch')
assert.ok(sessionsApi.includes('/recap'), 'recap fetch should call backend recap endpoint')
assert.ok(sessionsApi.includes('/trajectory'), 'trajectory fetch should call backend trajectory endpoint')
assert.ok(view.includes('loadSessionArtifacts'), 'runs view should load session recap and trajectory')
assert.ok(view.includes("t('runs.sessionRecap')"), 'runs view should render recap section')
assert.ok(view.includes("t('runs.sessionTrajectory')"), 'runs view should render trajectory section')
assert.ok(view.includes(':disabled="!selectedSessionId"'), 'save trajectory button should be disabled without a selected session')
assert.ok(zh.includes("sessionRecap: '会话摘要'"), 'Chinese locale should include recap label')
assert.ok(en.includes("sessionRecap: 'Session recap'"), 'English locale should include recap label')
