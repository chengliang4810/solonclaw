import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(
  new URL('../src/components/solonclaw/chat/SessionListItem.vue', import.meta.url),
  'utf8',
)

assert.doesNotMatch(
  source,
  /<button\b[\s\S]*<button class="session-item-delete"/,
  'session list rows should not nest the delete button inside another button',
)
assert.match(source, /role="button"/)
assert.match(source, /@keydown\.enter\.prevent="emit\('select'\)"/)
assert.match(source, /@keydown\.space\.prevent="emit\('select'\)"/)
