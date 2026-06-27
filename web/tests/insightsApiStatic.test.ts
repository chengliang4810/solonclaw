import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'

const insightsApiUrl = new URL('../src/api/solonclaw/insights.ts', import.meta.url)
assert.ok(existsSync(insightsApiUrl), 'insights API wrapper should exist')

const insightsApi = readFileSync(insightsApiUrl, 'utf8')
const usageView = readFileSync(new URL('../src/views/solonclaw/UsageView.vue', import.meta.url), 'utf8')
const skillsView = readFileSync(new URL('../src/views/solonclaw/SkillsView.vue', import.meta.url), 'utf8')

assert.ok(insightsApi.includes('/api/insights/overview'), 'overview endpoint should be wrapped')
assert.ok(insightsApi.includes('/api/insights/skills'), 'skills endpoint should be wrapped')
assert.ok(usageView.includes('fetchInsightsOverview'), 'Usage view should load insights overview')
assert.ok(skillsView.includes('fetchInsightsSkills'), 'Skills view should load insights skills')
