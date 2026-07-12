import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const input = readFileSync(new URL('../src/components/solonclaw/chat/ChatInput.vue', import.meta.url), 'utf8')
const handleSend = input.slice(input.indexOf('async function handleSend()'), input.indexOf('function handleCompositionStart()'))

assert.ok(handleSend.includes('const sent = await chatStore.sendMessage('), '发送结果返回前不得清空聊天草稿')
assert.ok(handleSend.includes('if (!sent) return'), '未进入发送流程时必须保留聊天草稿和附件')
assert.ok(
  handleSend.indexOf('if (!sent) return') < handleSend.indexOf("inputText.value = ''"),
  '草稿只能在确认消息已发送后清空',
)
assert.ok(handleSend.includes('isSending.value = true'), '发送请求期间必须防止重复 Enter 提交')
assert.ok(input.includes(':disabled="!canSend || chatStore.isStreaming || isSending"'), '发送请求期间必须禁用发送按钮')
