import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  providerAuthColor,
  providerAuthLabel,
  requiredSummary,
  statusLabel,
  statusTone,
} from '../src/shared/tuiRuntimeDisplay.ts'
import type { TuiChannelStatus } from '../src/api/solonclaw/tuiRuntime.ts'

const view = readFileSync(
  new URL('../src/views/solonclaw/TuiRuntimeView.vue', import.meta.url),
  'utf8',
)

const text = {
  authenticated: '已认证',
  configured: '已配置',
  disabled: '已停用',
  missingConfig: '缺少配置',
  noData: '暂无数据',
  unauthenticated: '未认证',
} as const

assert.equal(statusTone('configured'), 'success')
assert.equal(statusTone('missing_config'), 'warning')
assert.equal(statusTone('disabled'), 'default')
assert.equal(statusLabel('configured', text), '已配置')
assert.equal(statusLabel('missing_config', text), '缺少配置')
assert.equal(statusLabel('disabled', text), '已停用')
assert.equal(statusLabel('custom', text), 'custom')
assert.equal(statusLabel(undefined, text), '暂无数据')

assert.equal(providerAuthColor(true), 'success')
assert.equal(providerAuthColor(false), 'default')
assert.equal(providerAuthLabel(true, text), '已认证')
assert.equal(providerAuthLabel(false, text), '未认证')

const channel: TuiChannelStatus = {
  channel: 'dingtalk',
  key: 'dingtalk',
  label: '钉钉',
  configured: true,
  enabled: true,
  status: 'configured',
  required_keys: ['clientId', 'clientSecret', 'robotCode'],
  required_configured: { clientId: true, clientSecret: false, robotCode: true },
}

assert.equal(requiredSummary(channel), '2/3')
assert.ok(!view.includes('function statusTone'), 'TuiRuntimeView should not inline status tone mapping')
assert.ok(!view.includes('function statusLabel'), 'TuiRuntimeView should not inline status label mapping')
assert.ok(!view.includes('function providerAuthLabel'), 'TuiRuntimeView should not inline provider auth labels')
assert.ok(!view.includes('function providerAuthColor'), 'TuiRuntimeView should not inline provider auth colors')
assert.ok(!view.includes('function requiredSummary'), 'TuiRuntimeView should not inline required key summaries')
assert.ok(view.includes("from '@/shared/tuiRuntimeDisplay'"), 'TuiRuntimeView should reuse shared runtime display helpers')
