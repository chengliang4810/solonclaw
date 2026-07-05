import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const messageItem = readFileSync(
  new URL('../src/components/solonclaw/chat/MessageItem.vue', import.meta.url),
  'utf8',
)

assert.ok(
  messageItem.includes('formatTimestampMs') && messageItem.includes('@/shared/session-display'),
  'message items should reuse the shared timestamp formatter that hides empty timestamps',
)
assert.ok(
  messageItem.includes('formatTimestampMs(props.message.timestamp)'),
  'message time should go through the formatter instead of constructing Date directly',
)
assert.ok(
  messageItem.includes('v-if="timeStr" class="message-time"'),
  'message time should not render when restored messages have no timestamp',
)
assert.ok(
  !messageItem.includes('new Date(props.message.timestamp)'),
  'message items must not render epoch time for zero or missing timestamps',
)
