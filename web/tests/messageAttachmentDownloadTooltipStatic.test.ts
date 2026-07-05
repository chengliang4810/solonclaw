import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const messageItem = readFileSync(
  new URL('../src/components/solonclaw/chat/MessageItem.vue', import.meta.url),
  'utf8',
)

assert.ok(
  !messageItem.includes('download.downloadFile'),
  'chat attachment tooltip should not reference a missing download.downloadFile translation key',
)
assert.match(messageItem, /t\(['"]download\.download['"]\)/)
