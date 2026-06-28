import { describe, expect, it, vi } from 'vitest'

import { getOverlayState, patchOverlayState, resetOverlayState } from '../app/overlayStore.js'
import { getTurnState, resetTurnState } from '../app/turnStore.js'
import { getUiState, patchUiState, resetUiState } from '../app/uiStore.js'
import { applyVoiceRecordResponse, settleDeniedApprovalOverlay, shouldFallThroughForScroll } from '../app/useInputHandlers.js'

const baseKey = {
  downArrow: false,
  pageDown: false,
  pageUp: false,
  shift: false,
  upArrow: false,
  wheelDown: false,
  wheelUp: false
}

describe('shouldFallThroughForScroll — keep transcript scrolling alive during prompt overlays', () => {
  it('falls through for wheel scrolls', () => {
    expect(shouldFallThroughForScroll({ ...baseKey, wheelUp: true })).toBe(true)
    expect(shouldFallThroughForScroll({ ...baseKey, wheelDown: true })).toBe(true)
  })

  it('falls through for PageUp / PageDown', () => {
    expect(shouldFallThroughForScroll({ ...baseKey, pageUp: true })).toBe(true)
    expect(shouldFallThroughForScroll({ ...baseKey, pageDown: true })).toBe(true)
  })

  it('falls through for Shift+ArrowUp / Shift+ArrowDown', () => {
    expect(shouldFallThroughForScroll({ ...baseKey, shift: true, upArrow: true })).toBe(true)
    expect(shouldFallThroughForScroll({ ...baseKey, shift: true, downArrow: true })).toBe(true)
  })

  it('does NOT fall through for plain arrows — those drive in-prompt selection', () => {
    expect(shouldFallThroughForScroll({ ...baseKey, upArrow: true })).toBe(false)
    expect(shouldFallThroughForScroll({ ...baseKey, downArrow: true })).toBe(false)
  })

  it('does NOT fall through for plain Shift — without an arrow it is a no-op', () => {
    expect(shouldFallThroughForScroll({ ...baseKey, shift: true })).toBe(false)
  })

  it('does NOT fall through for unrelated state (no scroll keys held)', () => {
    expect(shouldFallThroughForScroll(baseKey)).toBe(false)
  })
})

describe('applyVoiceRecordResponse', () => {
  it('reverts optimistic REC state when the gateway reports voice busy', () => {
    const setProcessing = vi.fn()
    const setRecording = vi.fn()
    const sys = vi.fn()

    applyVoiceRecordResponse({ status: 'busy' }, true, { setProcessing, setRecording }, sys)

    expect(setRecording).toHaveBeenCalledWith(false)
    expect(setProcessing).toHaveBeenCalledWith(true)
    expect(sys).toHaveBeenCalledWith('voice: still transcribing; try again shortly')
  })

  it('keeps optimistic REC state for successful recording starts', () => {
    const setProcessing = vi.fn()
    const setRecording = vi.fn()

    applyVoiceRecordResponse({ status: 'recording' }, true, { setProcessing, setRecording }, vi.fn())

    expect(setRecording).not.toHaveBeenCalled()
    expect(setProcessing).not.toHaveBeenCalled()
  })

  it('reverts optimistic REC state when the gateway returns null', () => {
    const setProcessing = vi.fn()
    const setRecording = vi.fn()

    applyVoiceRecordResponse(null, true, { setProcessing, setRecording }, vi.fn())

    expect(setRecording).toHaveBeenCalledWith(false)
    expect(setProcessing).toHaveBeenCalledWith(false)
  })
})

describe('settleDeniedApprovalOverlay', () => {
  it('closes the approval overlay and returns the TUI to ready after Ctrl+C deny succeeds', () => {
    resetOverlayState()
    resetTurnState()
    resetUiState()
    patchOverlayState({
      approval: {
        approvalId: 'approval-1',
        command: 'printf audit',
        description: '需要审批',
        policy: 'policy:workspace_outside_write',
        sessionId: 'sid-abc'
      }
    })
    patchUiState({ busy: true, sid: 'sid-abc', status: '需要审批' })

    expect(settleDeniedApprovalOverlay({ denied: true, ok: true })).toBe(true)

    expect(getOverlayState().approval).toBeNull()
    expect(getTurnState().outcome).toBe('denied')
    expect(getUiState().busy).toBe(false)
    expect(getUiState().status).toBe('ready')
  })

  it('keeps the approval overlay open when the backend rejects the Ctrl+C deny response', () => {
    resetOverlayState()
    resetTurnState()
    resetUiState()
    patchOverlayState({
      approval: {
        approvalId: 'approval-1',
        command: 'printf audit',
        description: '需要审批',
        policy: 'policy:workspace_outside_write',
        sessionId: 'sid-abc'
      }
    })
    patchUiState({ busy: true, sid: 'sid-abc', status: '需要审批' })

    expect(settleDeniedApprovalOverlay({ ok: false, warning: 'approval_not_found' })).toBe(false)

    expect(getOverlayState().approval?.approvalId).toBe('approval-1')
    expect(getTurnState().outcome).toBe('')
    expect(getUiState().busy).toBe(true)
    expect(getUiState().status).toBe('需要审批')
  })
})
