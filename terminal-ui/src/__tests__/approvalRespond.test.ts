import { describe, expect, it } from 'vitest'

import { buildApprovalRespondParams } from '../domain/approvalRespond.js'

describe('buildApprovalRespondParams', () => {
  it('uses the approval request id, selected choice, and request session id', () => {
    expect(
      buildApprovalRespondParams(
        { approvalId: 'appr-123', sessionId: 'approval-session' },
        'session',
        'current-session'
      )
    ).toEqual({
      approval_id: 'appr-123',
      choice: 'session',
      session_id: 'approval-session'
    })
  })

  it('falls back to the current TUI session when the approval event has no session id', () => {
    expect(buildApprovalRespondParams({ approvalId: 'appr-123' }, 'deny', 'current-session')).toEqual({
      approval_id: 'appr-123',
      choice: 'deny',
      session_id: 'current-session'
    })
  })
})
