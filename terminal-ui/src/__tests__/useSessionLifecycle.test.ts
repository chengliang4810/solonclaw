import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { turnController } from '../app/turnController.js'
import { getTurnState, resetTurnState } from '../app/turnStore.js'
import { patchUiState, resetUiState } from '../app/uiStore.js'
import {
  clearActiveSessionFile,
  hydrateLiveSessionInflight,
  isLiveSessionRunning,
  isMissingSessionError,
  lastUserTextFromMessages,
  liveSessionInflightMessages,
  writeActiveSessionFile
} from '../app/useSessionLifecycle.js'

describe('writeActiveSessionFile', () => {
  let dir = ''

  afterEach(() => {
    if (dir) {
      rmSync(dir, { force: true, recursive: true })
      dir = ''
    }
  })

  it('does not block the TUI thread with synchronous active-session file writes', () => {
    const source = readFileSync(fileURLToPath(new URL('../app/useSessionLifecycle.ts', import.meta.url)), 'utf8')

    expect(source).not.toContain('writeFileSync')
  })

  it('writes the actual resumed session id and clears a stale pointer', async () => {
    dir = mkdtempSync(join(tmpdir(), 'solonclaw-tui-active-'))
    const path = join(dir, 'active.json')

    writeActiveSessionFile('actual_session', path)

    await waitForFile(path)

    expect(JSON.parse(readFileSync(path, 'utf8'))).toEqual({ session_id: 'actual_session' })
    clearActiveSessionFile(path)
    await waitForFile(path, false)

    expect(existsSync(path)).toBe(false)
  })
})

const waitForFile = async (path: string, expected = true) => {
  for (let i = 0; i < 20; i++) {
    if (existsSync(path) === expected) {
      return
    }

    await new Promise(resolve => setTimeout(resolve, 10))
  }
}

describe('session setup failure recovery', () => {
  const source = () => readFileSync(fileURLToPath(new URL('../app/useSessionLifecycle.ts', import.meta.url)), 'utf8')

  it('restores ready status when setup.status fails before session creation', () => {
    const startNewSession = blockBetween(source(), 'const startNewSession = useCallback', 'const newSession = useCallback')

    expect(startNewSession).toContain('catch')
    expect(startNewSession).toContain("sys(`error: ${sessionLifecycleErrorMessage")
    expect(startNewSession).toContain("patchUiState({ status: 'ready' })")
  })

  it('restores ready status when setup.status fails before session resume', () => {
    const resumeById = blockBetween(source(), 'const resumeById = useCallback', 'const guardBusySessionSwitch = useCallback')

    expect(resumeById).toContain('catch')
    expect(resumeById).toContain("sys(`error: ${sessionLifecycleErrorMessage")
    expect(resumeById).toContain("patchUiState({ status: 'ready' })")
  })

  it('keeps the current session until the requested session resumes successfully', () => {
    const resumeById = blockBetween(source(), 'const resumeById = useCallback', 'const guardBusySessionSwitch = useCallback')

    const requestIndex = resumeById.indexOf(".request<SessionResumeResponse>('session.resume'")
    const closeIndex = resumeById.indexOf('void closeSession(previousSid === id ? null : previousSid)')

    expect(requestIndex).toBeGreaterThanOrEqual(0)
    expect(closeIndex).toBeGreaterThan(requestIndex)
  })

  it('only removes local session state for an explicit missing-session response', () => {
    const resumeById = blockBetween(source(), 'const resumeById = useCallback', 'const guardBusySessionSwitch = useCallback')

    expect(isMissingSessionError(new Error('session not found: removed-session'))).toBe(true)
    expect(isMissingSessionError(new Error('gateway websocket closed'))).toBe(false)
    expect(resumeById).toContain('if (isMissingSessionError(e))')
    expect(resumeById).toContain('clearActiveSessionFile()')
    expect(resumeById).toContain('patchUiState({ busy: false })')
  })

  it('also removes local state when live-session activation finds a deleted session', () => {
    const activateLiveSession = blockBetween(source(), 'const activateLiveSession = useCallback', 'const resumeById = useCallback')

    expect(activateLiveSession).toContain('if (isMissingSessionError(e))')
    expect(activateLiveSession).toContain('resetSession()')
    expect(activateLiveSession).toContain('clearActiveSessionFile()')
    expect(activateLiveSession).toContain('patchUiState({ busy: false })')
  })
})

const blockBetween = (source: string, start: string, end: string) => {
  const startIndex = source.indexOf(start)
  expect(startIndex).toBeGreaterThanOrEqual(0)
  const endIndex = source.indexOf(end, startIndex)
  expect(endIndex).toBeGreaterThan(startIndex)

  return source.slice(startIndex, endIndex)
}

describe('live session activation in-flight state', () => {
  beforeEach(() => {
    resetUiState()
    resetTurnState()
    turnController.fullReset()
    patchUiState({ streaming: true })
  })

  it('keeps the in-flight user prompt in history and hydrates partial assistant text', () => {
    const inflight = { assistant: 'partial answer', streaming: true, user: 'write a long answer' }

    expect(liveSessionInflightMessages(inflight)).toEqual([{ role: 'user', text: 'write a long answer' }])

    hydrateLiveSessionInflight(inflight)

    expect(turnController.bufRef).toBe('partial answer')
    expect(getTurnState().streaming).toBe('partial answer')
  })

  it('ignores empty in-flight payloads', () => {
    expect(liveSessionInflightMessages({ assistant: '', streaming: false, user: '   ' })).toEqual([])

    hydrateLiveSessionInflight({ assistant: '', streaming: false, user: '' })

    expect(turnController.bufRef).toBe('')
    expect(getTurnState().streaming).toBe('')
  })
})

describe('live session resume status', () => {
  it('treats resumed waiting and working sessions as running', () => {
    expect(isLiveSessionRunning(false, 'waiting')).toBe(true)
    expect(isLiveSessionRunning(false, 'working')).toBe(true)
    expect(isLiveSessionRunning(true, 'idle')).toBe(true)
    expect(isLiveSessionRunning(false, 'idle')).toBe(false)
  })
})

describe('resume retry source', () => {
  it('uses the last restored user message as the retry source', () => {
    expect(
      lastUserTextFromMessages([
        { role: 'user', text: 'first prompt' },
        { role: 'assistant', text: 'first answer' },
        { role: 'tool', text: 'tool output' },
        { role: 'user', text: 'latest prompt' },
        { role: 'assistant', text: 'latest answer' }
      ])
    ).toBe('latest prompt')
  })
})
