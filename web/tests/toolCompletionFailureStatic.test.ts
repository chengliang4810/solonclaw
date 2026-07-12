import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const store = readFileSync(new URL('../src/stores/solonclaw/chat.ts', import.meta.url), 'utf8')
const completed = store.slice(store.indexOf("case 'tool.completed':"), store.indexOf("case 'run.completed':"))
const streamError = store.slice(store.indexOf('// onError'), store.indexOf('startingProfile,'))
const replay = store.slice(store.indexOf('function mapSolonClawMessages'), store.indexOf('export const useChatStore'))

assert.ok(completed.includes("evt.status === 'error'"), 'Dashboard 必须消费工具完成事件的结构化失败状态')
assert.ok(completed.includes("toolStatus: error ? 'error' : 'done'"), '工具失败不得渲染为完成')
assert.ok(completed.includes('toolResult: error || undefined'), '失败原因必须可在工具详情中查看')
assert.ok(completed.includes("} else if (error) {"), '未配对的工具失败完成事件必须创建错误工具块')
assert.ok(completed.includes("toolStatus: 'error'"), '未配对的工具失败块必须标记为错误')
assert.ok(!streamError.includes("toolStatus: 'done'"), 'SSE 断开时不得把运行中的工具误标为完成')
assert.ok(replay.includes("msg.tool_status === 'error'"), '回放必须优先消费会话 API 的工具失败状态')
assert.ok(replay.includes("!msg.tool_status && parsed.status === 'error'"), '旧会话应兼容统一工具错误 envelope')
assert.ok(replay.includes('toolStatus: replayStatus'), '回放工具块必须使用解析后的持久化状态')
