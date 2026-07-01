import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  formatJobTime,
  humanizeGuideToken,
  humanizeJobToken,
  inferJobScheduleKind,
  jobActionSummary,
  jobAliasSummary,
  jobBadges,
  jobDeliveryTargetLabel,
  jobListDetail,
  jobMapKeysText,
  jobScheduleLabel,
  jobStatusLabel,
  jobStatusTone,
  jobTokenListText,
  jobValueList,
  jobValueText,
  joinJobDetailParts,
  type DashboardTranslator,
} from '../src/shared/jobsDisplay.ts'

const labels: Record<string, string> = {
  'common.delete': '删除',
  'common.edit': '编辑',
  'jobs.action.history': '历史',
  'jobs.action.pause': '暂停',
  'jobs.action.resume': '恢复',
  'jobs.action.retry': '重试',
  'jobs.action.runNow': '立即运行',
  'jobs.alias.disableStop': '停用/停止',
  'jobs.alias.enableStart': '启用/启动',
  'jobs.alias.retryRerun': '重试/重跑',
  'jobs.badge.context': '{count} 个上游',
  'jobs.badge.noAgent': '脚本直投',
  'jobs.badge.pendingTrigger': '待执行：{trigger}',
  'jobs.badge.script': '脚本',
  'jobs.badge.skills': '{count} 个技能',
  'jobs.badge.toolsets': '{count} 个工具集',
  'jobs.badge.wrapResponse': '包装投递',
  'jobs.humanize.cron': 'Cron 表达式',
  'jobs.humanize.interval': '间隔执行',
  'jobs.humanize.local': '本地会话',
  'jobs.humanize.scheduled': '定时触发',
  'jobs.status.disabled': '已停用',
  'jobs.status.paused': '已暂停',
  'jobs.status.running': '运行中',
  'jobs.status.scheduled': '已计划',
  'jobs.fieldHumanize.update': '更新',
  'jobs.fieldHumanize.fields': '字段',
  'jobs.actionHumanize.retry': '重试',
  'jobs.detail.yes': '是',
  'jobs.detail.no': '否',
}

const t: DashboardTranslator = (key, params) => {
  const template = labels[key] || key
  return Object.entries(params || {}).reduce(
    (text, [name, value]) => text.replace(`{${name}}`, String(value)),
    template,
  )
}
const jobCard = readFileSync(
  new URL('../src/components/solonclaw/jobs/JobCard.vue', import.meta.url),
  'utf8',
)
const jobsView = readFileSync(
  new URL('../src/views/solonclaw/JobsView.vue', import.meta.url),
  'utf8',
)

assert.equal(humanizeJobToken(t, 'local'), '本地会话')
assert.equal(humanizeJobToken(t, 'unknown'), 'unknown')
assert.equal(humanizeJobToken(t, ' ', { fallback: '—' }), '—')
assert.equal(humanizeGuideToken(t, 'update_fields'), '更新 / 字段')
assert.equal(jobTokenListText(t, ['cron', 'interval'], { guide: true }), 'Cron 表达式、间隔执行')
assert.equal(jobMapKeysText(t, { retry: true }), '重试')
assert.equal(jobValueText(t, true), '是')
assert.equal(jobValueText(t, ['cron', 'missing']), 'Cron 表达式、missing')
assert.deepEqual(jobValueList(t, ['cron', '']), ['Cron 表达式'])
assert.deepEqual(jobValueList(t, '  raw  '), ['raw'])
assert.equal(jobScheduleLabel({ schedule: { kind: 'cron', display: '每天 9 点', expr: '0 9 * * *' } }), '每天 9 点')
assert.equal(jobScheduleLabel({ schedule: { kind: 'cron', expr: '0 9 * * *' } }), '0 9 * * *')
assert.equal(jobScheduleLabel({ schedule_display: '备用展示' }), '备用展示')
assert.equal(inferJobScheduleKind({ schedule: { kind: 'interval', display: 'every 5m' } }), 'interval')
assert.equal(inferJobScheduleKind({ schedule: '2026-06-17T08:00:00' }), 'once')
assert.equal(inferJobScheduleKind({ schedule: 'every 5m' }), 'interval')
assert.equal(inferJobScheduleKind({ schedule: '0 9 * * *' }), 'cron')
assert.equal(jobStatusLabel(t, { state: 'running', enabled: true }), '运行中')
assert.equal(jobStatusLabel(t, { state: 'paused', enabled: true }), '已暂停')
assert.equal(jobStatusLabel(t, { state: 'idle', enabled: false }), '已停用')
assert.equal(jobStatusLabel(t, { state: 'idle', enabled: true }), '已计划')
assert.equal(jobStatusTone({ state: 'running', enabled: true }), 'info')
assert.equal(jobStatusTone({ state: 'paused', enabled: true }), 'warning')
assert.equal(jobStatusTone({ state: 'idle', enabled: false }), 'error')
assert.equal(jobStatusTone({ state: 'idle', enabled: true }), 'success')
assert.equal(
  jobActionSummary(t, { can_pause: true, can_run: true, can_history: true, can_edit: true, can_remove: true }),
  '暂停、立即运行、历史、编辑、删除',
)
assert.equal(jobActionSummary(t, { can_run: false, can_history: false, can_edit: false, can_remove: false }), '—')
assert.equal(
  jobAliasSummary(t, { supports_enable_alias: true, supports_disable_alias: true, supports_rerun_alias: true }),
  '启用/启动、停用/停止、重试/重跑',
)
assert.equal(jobAliasSummary(t, {}), '—')
assert.deepEqual(jobBadges(t, {}), [])
assert.deepEqual(
  jobBadges(t, {
    pending_trigger: 'manual',
    no_agent: true,
    script: 'echo ok',
    wrap_response: true,
    skills: ['a', 'b'],
    context_from: ['upstream'],
    enabled_toolsets: ['shell', 'files'],
    provider: 'openai',
    model: 'gpt-4o',
  }),
  ['待执行：manual', '脚本直投', '脚本', '包装投递', '2 个技能', '1 个上游', '2 个工具集', 'openai:gpt-4o'],
)
assert.deepEqual(jobBadges(t, { model: 'local-model', provider: null }), ['local-model'])
assert.equal(formatJobTime(null), '—')
assert.equal(jobListDetail(['a', 'b']), 'a, b')
assert.equal(jobListDetail([]), '—')
assert.equal(joinJobDetailParts(['local', '', undefined, '#thread']), 'local · #thread')
assert.equal(
  jobDeliveryTargetLabel(t, { platform: 'local', chat_id: 'chat-1', thread_id: 'thread-1' }),
  '本地会话 · chat-1 · #thread-1',
)
assert.equal(jobDeliveryTargetLabel(t, { platform: null, chat_id: null, thread_id: null }), '— · —')
assert.ok(!jobCard.includes("props.job.state === 'running'"), 'JobCard should not inline job status state branches')
assert.ok(!jobCard.includes("actions.push(t('jobs.action.pause'))"), 'JobCard should not inline action summary labels')
assert.ok(!jobCard.includes("aliases.push(t('jobs.alias.enableStart'))"), 'JobCard should not inline alias summary labels')
assert.ok(!jobCard.includes("badges.push(t('jobs.badge.noAgent'))"), 'JobCard should not inline badge labels')
assert.ok(!jobCard.includes('function deliveryTargetLabel'), 'JobCard should not inline delivery target labels')
assert.ok(jobCard.includes('jobStatusLabel(t, props.job)'), 'JobCard should reuse shared job status labels')
assert.ok(jobCard.includes('jobStatusTone(props.job)'), 'JobCard should reuse shared job status tones')
assert.ok(jobCard.includes('jobActionSummary(t, actionFlags.value)'), 'JobCard should reuse shared action summaries')
assert.ok(jobCard.includes('jobAliasSummary(t, actionFlags.value)'), 'JobCard should reuse shared alias summaries')
assert.ok(jobCard.includes('jobBadges(t, props.job)'), 'JobCard should reuse shared badge summaries')
assert.ok(jobCard.includes('jobDeliveryTargetLabel(t, target)'), 'JobCard should reuse shared delivery target labels')
assert.ok(!jobsView.includes('simpleHero'), 'JobsView should not keep a how-to hero above the operational job list')
assert.ok(!jobsView.includes('pageNextRuns'), 'JobsView should not duplicate next runs outside the upcoming jobs panel')
