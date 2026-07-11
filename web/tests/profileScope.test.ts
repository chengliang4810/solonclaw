import assert from 'node:assert/strict'
import { appendManagementProfile, isProfileScopedApiPath, normalizeManagementProfile, profileSessionIdentity } from '../src/shared/profileScope.ts'

assert.equal(isProfileScopedApiPath('/api/config'), true)
assert.equal(isProfileScopedApiPath('/api/skills/toggle'), true)
assert.equal(isProfileScopedApiPath('/api/search/sessions'), true)
assert.equal(isProfileScopedApiPath('/api/runs/active'), true)
assert.equal(isProfileScopedApiPath('/api/checkpoints/session-1'), true)
assert.equal(isProfileScopedApiPath('/api/solonclaw/download'), true)
assert.equal(isProfileScopedApiPath('/api/profiles'), false)
assert.equal(appendManagementProfile('/api/config?raw=true', 'coder'), '/api/config?raw=true&profile=coder')
assert.equal(appendManagementProfile('/api/profiles', 'coder'), '/api/profiles')
assert.equal(
  appendManagementProfile('http://127.0.0.1:8080/api/mcp?profile=old', 'coder'),
  'http://127.0.0.1:8080/api/mcp?profile=old',
)
assert.equal(appendManagementProfile('/api/sessions/id?profile=worker', 'coder'), '/api/sessions/id?profile=worker')
assert.equal(normalizeManagementProfile('default', 'default'), '')
assert.equal(normalizeManagementProfile('coder', 'default'), 'coder')
assert.notEqual(profileSessionIdentity('same-id', 'default'), profileSessionIdentity('same-id', 'worker'))
assert.equal(profileSessionIdentity('same:id', 'worker/name'), 'worker%2Fname:same%3Aid')
