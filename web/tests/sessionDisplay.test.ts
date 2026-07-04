import assert from 'node:assert/strict'
import { normalizeTimestampMs } from '../src/shared/session-display.ts'

const ms = 1783205454430
const seconds = Math.round(ms / 1000)

assert.equal(normalizeTimestampMs(ms), ms)
assert.equal(normalizeTimestampMs(seconds), seconds * 1000)
assert.equal(normalizeTimestampMs(null), 0)
assert.equal(normalizeTimestampMs(undefined), 0)
