import assert from 'node:assert/strict'
import { mergeRefreshedSessions } from '../src/shared/chatSessionRefresh.ts'

const cached = [
  { id: 'old-session', messages: ['cached'] },
  { id: 'running-session', messages: ['local'] },
]

assert.deepEqual(
  mergeRefreshedSessions(cached, [], id => id === 'running-session'),
  [{ id: 'running-session', messages: ['local'] }],
)

assert.deepEqual(
  mergeRefreshedSessions(
    cached,
    [{ id: 'old-session', messages: [] }],
    () => false,
  ),
  [{ id: 'old-session', messages: ['cached'] }],
)
