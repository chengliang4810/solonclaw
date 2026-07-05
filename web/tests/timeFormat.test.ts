import assert from 'node:assert/strict'
import { formatTimestampText } from '../src/shared/timeFormat.ts'

const timestamp = 1710000000000

assert.equal(formatTimestampText(undefined), '-')
assert.equal(formatTimestampText(null), '-')
assert.equal(formatTimestampText(0), '-')
assert.equal(formatTimestampText(null, undefined, '—'), '—')
assert.equal(formatTimestampText(timestamp), new Date(timestamp).toLocaleString())
assert.equal(formatTimestampText(timestamp, 'zh-CN'), new Date(timestamp).toLocaleString('zh-CN'))
