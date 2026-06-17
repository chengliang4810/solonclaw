import assert from 'node:assert/strict'
import {
  formatJobTime,
  humanizeGuideToken,
  humanizeJobToken,
  inferJobScheduleKind,
  jobListDetail,
  jobMapKeysText,
  jobScheduleLabel,
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
  'jobs.fieldHumanize.update': '更新',
  'jobs.fieldHumanize.fields': '字段',
  'jobs.actionHumanize.retry': '重试',
  'jobs.detail.yes': '是',
  'jobs.detail.no': '否',
}

const t: DashboardTranslator = (key) => labels[key] || key

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
assert.equal(formatJobTime(null), '—')
assert.equal(jobListDetail(['a', 'b']), 'a, b')
assert.equal(jobListDetail([]), '—')
assert.equal(joinJobDetailParts(['local', '', undefined, '#thread']), 'local · #thread')
