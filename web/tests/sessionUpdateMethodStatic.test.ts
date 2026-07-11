import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/sessions.ts', import.meta.url), 'utf8')

assert.match(
  api,
  /renameSession[\s\S]*method:\s*'PATCH'/,
  'session title updates should use the PATCH contract',
)

