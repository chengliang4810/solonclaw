import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const store = readFileSync(new URL('../src/stores/solonclaw/chat.ts', import.meta.url), 'utf8')
const completed = store.slice(store.indexOf("case 'tool.completed':"), store.indexOf("case 'run.completed':"))

assert.ok(completed.includes("evt.status === 'error'"), 'Dashboard 必须消费工具完成事件的结构化失败状态')
assert.ok(completed.includes("toolStatus: error ? 'error' : 'done'"), '工具失败不得渲染为完成')
assert.ok(completed.includes('toolResult: error || undefined'), '失败原因必须可在工具详情中查看')
