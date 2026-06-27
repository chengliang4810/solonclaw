import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/system.ts', import.meta.url), 'utf8')
const modal = readFileSync(new URL('../src/components/solonclaw/models/ProviderFormModal.vue', import.meta.url), 'utf8')

assert.ok(api.includes('/api/providers/validate'), 'provider validation endpoint should be wrapped by the frontend API client')
assert.ok(modal.includes('validateProviderConfig'), 'provider form should expose configuration validation')
