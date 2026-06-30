import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  runArtifactText,
  runBooleanLabel,
  runStatusLabel,
  runTimestampText,
  type RunsDisplayTranslator,
} from '../src/shared/runsDisplay.ts'

const view = readFileSync(
  new URL('../src/views/solonclaw/RunsView.vue', import.meta.url),
  'utf8',
)

const labels: Record<string, string> = {
  'common.no': '否',
  'common.yes': '是',
  'runs.noSessionArtifact': '暂无会话产物',
  'runs.status.failed': '失败',
  'runs.status.running': '运行中',
  'runs.status.success': '成功',
}

const t: RunsDisplayTranslator = key => labels[key] || key

assert.equal(runTimestampText(undefined), '-')
assert.equal(runTimestampText(0), '-')
assert.match(runTimestampText(Date.UTC(2026, 0, 2, 3, 4, 5)), /2026/)

assert.equal(runStatusLabel('ok', t), '成功')
assert.equal(runStatusLabel('error', t), '失败')
assert.equal(runStatusLabel('failed', t), '失败')
assert.equal(runStatusLabel('running', t), '运行中')
assert.equal(runStatusLabel('paused', t), 'paused')
assert.equal(runStatusLabel(undefined, t), '-')

assert.equal(runBooleanLabel(true, t), '是')
assert.equal(runBooleanLabel(false, t), '否')
assert.equal(runBooleanLabel(undefined, t), '-')

assert.equal(runArtifactText('', t), '暂无会话产物')
assert.equal(runArtifactText(0, t), '暂无会话产物')
assert.equal(runArtifactText(false, t), '暂无会话产物')
assert.equal(runArtifactText('plain artifact', t), 'plain artifact')
assert.equal(runArtifactText({ node: 'main' }, t), '{\n  "node": "main"\n}')

assert.ok(!view.includes('function time('), 'RunsView should not inline timestamp formatting')
assert.ok(!view.includes('function statusLabel'), 'RunsView should not inline run status labels')
assert.ok(!view.includes('function booleanLabel'), 'RunsView should not inline boolean labels')
assert.ok(!view.includes('function artifactText'), 'RunsView should not inline artifact JSON formatting')
assert.ok(view.includes("from '@/shared/runsDisplay'"), 'RunsView should reuse shared run display helpers')
