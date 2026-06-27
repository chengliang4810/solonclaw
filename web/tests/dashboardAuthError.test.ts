import assert from 'node:assert/strict'
import { isDashboardOriginRejected } from '../src/api/dashboardAuthError.ts'

assert.equal(
  isDashboardOriginRejected(403, '{"detail":"Forbidden dashboard request origin"}'),
  true,
)
assert.equal(isDashboardOriginRejected(403, '{"detail":"Unauthorized"}'), false)
assert.equal(isDashboardOriginRejected(401, '{"detail":"Forbidden dashboard request origin"}'), false)
