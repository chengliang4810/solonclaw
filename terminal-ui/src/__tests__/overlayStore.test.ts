import { describe, expect, it } from 'vitest'

import { dismissApprovalIfCurrent, getOverlayState, patchOverlayState, resetOverlayState } from '../app/overlayStore.js'

describe('dismissApprovalIfCurrent', () => {
  it('dismisses the approval only when the current approval id still matches', () => {
    resetOverlayState()
    patchOverlayState({
      approval: {
        approvalId: 'approval-1',
        command: 'first',
        description: 'first approval',
        sessionId: 'sid-1'
      }
    })

    dismissApprovalIfCurrent('approval-1')

    expect(getOverlayState().approval).toBeNull()
  })

  it('keeps a newer approval request when an older approval response resolves later', () => {
    resetOverlayState()
    patchOverlayState({
      approval: {
        approvalId: 'approval-2',
        command: 'second',
        description: 'second approval',
        sessionId: 'sid-1'
      }
    })

    dismissApprovalIfCurrent('approval-1')

    expect(getOverlayState().approval).toMatchObject({
      approvalId: 'approval-2',
      description: 'second approval'
    })
  })
})
