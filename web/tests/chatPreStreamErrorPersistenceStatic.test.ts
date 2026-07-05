import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../src/stores/solonclaw/chat.ts', import.meta.url), 'utf8')

function blockBetween(start: string, end: string): string {
  const startIndex = source.indexOf(start)
  assert.notEqual(startIndex, -1, `missing block start: ${start}`)
  const endIndex = source.indexOf(end, startIndex)
  assert.notEqual(endIndex, -1, `missing block end: ${end}`)
  return source.slice(startIndex, endIndex)
}

const noRunIdBlock = blockBetween('if (!runId) {', 'const sid = adoptServerSessionId')
assert.ok(
  noRunIdBlock.includes('persistActiveMessages()'),
  'startRun without a run id should persist the visible error message before returning',
)

const catchBlock = blockBetween('} catch (err: any) {', 'async function sendSlashCommand')
assert.ok(
  catchBlock.includes('persistActiveMessages()'),
  'startRun/upload failures should persist the visible error message before leaving sendMessage',
)
