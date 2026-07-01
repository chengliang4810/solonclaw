import { describe, expect, it } from 'vitest'

import {
  consumePagerCloseInputSuppression,
  dismissApprovalIfCurrent,
  getOverlayState,
  notePagerClosedByKeyboard,
  patchOverlayState,
  resetOverlayState
} from '../app/overlayStore.js'

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

describe('pager close input suppression', () => {
  it('swallows one immediate q after keyboard-closing the pager', () => {
    notePagerClosedByKeyboard(1000)

    expect(consumePagerCloseInputSuppression('q', 1100)).toBe(true)
    expect(consumePagerCloseInputSuppression('q', 1101)).toBe(false)
  })

  it('does not swallow commands or late q input', () => {
    notePagerClosedByKeyboard(2000)

    expect(consumePagerCloseInputSuppression('/', 2050)).toBe(false)
    expect(consumePagerCloseInputSuppression('q', 3000)).toBe(false)
  })
})
