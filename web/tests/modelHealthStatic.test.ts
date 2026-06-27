import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/system.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/ModelsView.vue', import.meta.url), 'utf8')

assert.ok(api.includes('/api/models/health'), 'model health endpoint should be wrapped by the frontend API client')
assert.ok(view.includes('fetchModelHealth'), 'model health should be reachable from the Models UI')
