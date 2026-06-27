import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/curator.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/SkillsView.vue', import.meta.url), 'utf8')

for (const endpoint of ['/api/curator/improvements', '/api/curator/apply', '/api/curator/ignore']) {
  assert.ok(api.includes(endpoint), `${endpoint} should be wrapped by the frontend API client`)
}

for (const symbol of ['fetchCuratorImprovements', 'applyCuratorSuggestion', 'ignoreCuratorSuggestion']) {
  assert.ok(view.includes(symbol), `${symbol} should be reachable from the Skills UI`)
}
