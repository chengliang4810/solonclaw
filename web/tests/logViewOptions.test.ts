import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  LOG_LEVEL_OPTIONS,
  LOG_LINE_COUNT_OPTIONS,
  logLevelClass,
  translateLogLevelOptions,
} from '../src/shared/logViewOptions.ts'

const logsView = readFileSync(
  new URL('../src/views/solonclaw/LogsView.vue', import.meta.url),
  'utf8',
)

assert.deepEqual(LOG_LEVEL_OPTIONS.map(item => item.value), ['', 'ERROR', 'WARNING', 'INFO', 'DEBUG'])
assert.deepEqual(LOG_LINE_COUNT_OPTIONS.map(item => item.value), [50, 100, 200, 500])

assert.equal(logLevelClass('ERROR'), 'level-error')
assert.equal(logLevelClass('WARNING'), 'level-warning')
assert.equal(logLevelClass('DEBUG'), 'level-debug')
assert.equal(logLevelClass('INFO'), 'level-info')
assert.equal(logLevelClass('TRACE'), 'level-info')

const translated = translateLogLevelOptions(key => `label:${key}`)
assert.equal(translated[0]?.label, 'label:logs.all')
assert.equal(translated[0]?.value, '')

assert.ok(!logsView.includes('const levelOptions = computed(() => ['), 'logs view should not inline level options')
assert.ok(!logsView.includes('const lineOptions = ['), 'logs view should not inline line-count options')
assert.ok(!logsView.includes('function levelClass'), 'logs view should reuse shared level class mapping')
assert.ok(
  logsView.includes('<style scoped lang="scss" src="../../styles/logsView.scss"></style>'),
  'logs view styles should be moved to the shared styles directory',
)
