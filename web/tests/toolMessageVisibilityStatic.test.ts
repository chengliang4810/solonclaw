import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const messageList = readFileSync(new URL('../src/components/solonclaw/chat/MessageList.vue', import.meta.url), 'utf8')
const messageItem = readFileSync(new URL('../src/components/solonclaw/chat/MessageItem.vue', import.meta.url), 'utf8')

assert.ok(
  messageItem.includes("message.role === 'tool'"),
  'MessageItem should render tool messages',
)
assert.ok(
  !messageList.includes('m.role !== "tool"'),
  'MessageList should not drop every tool message from history',
)
assert.ok(
  messageList.includes('currentToolCallIds'),
  'MessageList should only suppress current active tool calls to avoid duplicating the live tool panel',
)
