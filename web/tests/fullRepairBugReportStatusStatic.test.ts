import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const report = readFileSync(new URL('../../docs/full-repair-bug-report-2026-07-05.md', import.meta.url), 'utf8')
const conclusion = report.split('## 当前结论')[1] || ''
const fixedBugIds = [...report.matchAll(/## (BUG-\d+)：[^\n]+\n\n状态：已修复/g)].map((match) => match[1])

for (const bugId of fixedBugIds) {
  assert.equal(
    new RegExp(`${bugId}[^\\n]*待复核`).test(conclusion),
    false,
    `${bugId} is fixed but still listed as pending review`,
  )
}
