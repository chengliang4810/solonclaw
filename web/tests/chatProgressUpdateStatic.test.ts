import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const storeSource = readFileSync(new URL('../src/stores/solonclaw/chat.ts', import.meta.url), 'utf8')
const itemSource = readFileSync(new URL('../src/components/solonclaw/chat/MessageItem.vue', import.meta.url), 'utf8')

assert.match(storeSource, /case 'progress\.update':/)
assert.match(storeSource, /isProgress: true/)
assert.match(
  storeSource,
  /isProgress: msg\.role === 'assistant' && Boolean\(msg\.tool_calls\?\.length && normalized\.content\.trim\(\)\)/,
)
assert.match(itemSource, /progress: message\.isProgress/)
assert.match(itemSource, /&\.progress/)
