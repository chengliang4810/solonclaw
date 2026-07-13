import { describe, expect, it, vi } from 'vitest'

const capturedInput = vi.hoisted(() => ({ handler: undefined as undefined | ((ch: string, key: any) => void) }))

vi.mock('@nanostores/react', () => ({ useStore: (store: { get: () => unknown }) => store.get() }))
vi.mock('@solonclaw/ink', async importOriginal => ({
  ...(await importOriginal()),
  forceRedraw: vi.fn(),
  useInput: (handler: (ch: string, key: any) => void) => {
    capturedInput.handler = handler
  }
}))
vi.mock('react', async importOriginal => {
  const actual = await importOriginal()

  return { ...actual, useEffect: () => undefined, useRef: <T>(value: T) => ({ current: value }) }
})

import { getOverlayState, patchOverlayState, resetOverlayState } from '../app/overlayStore.js'
import { getTurnState, resetTurnState } from '../app/turnStore.js'
import { getUiState, patchUiState, resetUiState } from '../app/uiStore.js'
import {
  applyVoiceRecordResponse,
  settleDeniedApprovalOverlay,
  shouldFallThroughForScroll,
  useInputHandlers
} from '../app/useInputHandlers.js'

const baseKey = {
  downArrow: false,
  pageDown: false,
  pageUp: false,
  shift: false,
  upArrow: false,
  wheelDown: false,
  wheelUp: false
}

const mountInputHandler = (rpc: ReturnType<typeof vi.fn>) => {
  capturedInput.handler = undefined
  // 测试中以受控 mock 调用 Hook，捕获注册的终端按键回调。
  // eslint-disable-next-line react-hooks/rules-of-hooks
  useInputHandlers({
    actions: {
      answerClarify: vi.fn(),
      appendMessage: vi.fn(),
      die: vi.fn(),
      dispatchSubmission: vi.fn(),
      guardBusySessionSwitch: vi.fn(),
      newSession: vi.fn(),
      sys: vi.fn()
    },
    composer: { actions: {}, refs: {}, state: {} },
    gateway: { rpc },
    terminal: { scrollWithSelection: vi.fn(), selection: {}, stdout: { rows: 24 } },
    voice: {
      enabled: false,
      recordKey: {},
      recording: false,
      setProcessing: vi.fn(),
      setRecording: vi.fn(),
      setVoiceEnabled: vi.fn(),
      setVoiceTts: vi.fn()
    },
    wheelStep: 3
  } as any)

  return capturedInput.handler!
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

describe('敏感输入 Esc 取消', () => {
  it('在 sudo 取消 RPC 未完成前立即清除遮罩', () => {
    resetOverlayState()
    patchOverlayState({ sudo: { requestId: 'sudo-1' } })
    const rpc = vi.fn(() => new Promise(() => undefined))

    mountInputHandler(rpc)('', { ctrl: false, escape: true })

    expect(getOverlayState().sudo).toBeNull()
    expect(rpc).toHaveBeenCalledWith('sudo.respond', { password: '', request_id: 'sudo-1' })
  })

  it('在 secret 取消 RPC 返回空值或失败时保持遮罩已关闭', () => {
    resetOverlayState()
    patchOverlayState({ secret: { envVar: 'API_KEY', prompt: '请输入密钥', requestId: 'secret-1' } })
    const rejected = Promise.reject(new Error('gateway unavailable'))
    rejected.catch(() => undefined)
    const rpc = vi.fn(() => rejected)

    mountInputHandler(rpc)('', { ctrl: false, escape: true })

    expect(getOverlayState().secret).toBeNull()
    expect(rpc).toHaveBeenCalledWith('secret.respond', { request_id: 'secret-1', value: '' })
  })
})
