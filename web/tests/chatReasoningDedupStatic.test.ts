import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const store = readFileSync(new URL('../src/stores/solonclaw/chat.ts', import.meta.url), 'utf8')
const input = readFileSync(new URL('../src/components/solonclaw/chat/ChatInput.vue', import.meta.url), 'utf8')

assert.ok(store.includes('splitLegacyThinkContent'), '历史消息映射必须剥离正文中的 think 标签')
assert.ok(store.includes('normalizeCachedMessages'), '浏览器旧缓存也必须在消息合并前清洗')
assert.ok(
  store.includes("!msgs.some(m => m.role === 'assistant' && m.reasoning?.trim())"),
  '完成事件不得把本轮已有的 reasoning 再挂到最终消息',
)
assert.ok(input.includes('border: 0 !important'), '聊天输入框应只保留外层容器边框')
assert.ok(input.includes('box-shadow: none !important'), '聊天输入框应覆盖全局聚焦阴影')
