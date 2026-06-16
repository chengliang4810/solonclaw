import assert from 'node:assert/strict'
import { selectSessionId } from '../src/shared/sessionSelection.ts'

const sessions = [
  { id: 'recent-session' },
  { id: 'saved-session' },
  { id: 'url-session' },
]

assert.equal(
  selectSessionId(sessions, 'url-session', 'saved-session'),
  'url-session',
)

assert.equal(
  selectSessionId(sessions, 'missing-url-session', 'saved-session'),
  'saved-session',
)

assert.equal(
  selectSessionId(sessions, null, 'missing-saved-session'),
  'recent-session',
)

assert.equal(
  selectSessionId([], 'url-session', 'saved-session'),
  undefined,
)
