import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  formatJobTime,
  humanizeGuideToken,
  humanizeJobToken,
  inferJobScheduleKind,
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

const t: DashboardTranslator = (key) => labels[key] || key
const jobCard = readFileSync(
  new URL('../src/components/solonclaw/jobs/JobCard.vue', import.meta.url),
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
assert.equal(formatJobTime(null), '—')
assert.equal(jobListDetail(['a', 'b']), 'a, b')
assert.equal(jobListDetail([]), '—')
assert.equal(joinJobDetailParts(['local', '', undefined, '#thread']), 'local · #thread')
assert.ok(!jobCard.includes("props.job.state === 'running'"), 'JobCard should not inline job status state branches')
assert.ok(jobCard.includes('jobStatusLabel(t, props.job)'), 'JobCard should reuse shared job status labels')
assert.ok(jobCard.includes('jobStatusTone(props.job)'), 'JobCard should reuse shared job status tones')
