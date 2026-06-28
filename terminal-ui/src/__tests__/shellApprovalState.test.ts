import { describe, expect, it } from 'vitest'

import { approvalFromShellResponse, shellExecSettledUiState } from '../app/useSubmission.js'

describe('shellExecSettledUiState', () => {
  it('keeps the terminal busy while a shell command is waiting for approval', () => {
    expect(shellExecSettledUiState(true)).toEqual({ busy: true, status: '需要审批' })
  })

  it('returns the terminal to ready after normal shell command completion', () => {
    expect(shellExecSettledUiState(false)).toEqual({ busy: false, status: 'ready' })
  })

  it('maps a chained shell approval response into the next approval overlay', () => {
    expect(
      approvalFromShellResponse(
        {
          next_approval: {
            approval_id: 'approval-2',
            command: 'curl -fsS https://example.com',
            description: '网络外部操作需要审批',
            session_id: 'sid-from-response'
          }
        },
        'sid-current'
      )
    ).toEqual({
      approvalId: 'approval-2',
      command: 'curl -fsS https://example.com',
      description: '网络外部操作需要审批',
      sessionId: 'sid-from-response'
    })
  })

  it('falls back to the current session id when the chained approval omits session id', () => {
    expect(
      approvalFromShellResponse(
        {
          next_approval: {
            approval_id: 'approval-2',
            command: 'curl -fsS https://example.com',
            description: '网络外部操作需要审批'
          }
        },
        'sid-current'
      )
    ).toMatchObject({ sessionId: 'sid-current' })
  })
})
