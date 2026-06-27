import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'

for (const path of [
  '../src/api/solonclaw/codex-auth.ts',
  '../src/api/solonclaw/nous-auth.ts',
  '../src/components/solonclaw/models/CodexLoginModal.vue',
  '../src/components/solonclaw/models/NousLoginModal.vue',
]) {
  assert.equal(existsSync(new URL(path, import.meta.url)), false, `${path} should not remain as an unsupported login stub`)
}
