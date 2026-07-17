import { PassThrough } from 'stream'

import { renderSync } from '@solonclaw/ink'
import React, { useImperativeHandle } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { resetUiState } from '../app/uiStore.js'
import type { UseSubmissionOptions } from '../app/useSubmission.js'
import { useSubmission } from '../app/useSubmission.js'

type SubmissionApi = ReturnType<typeof useSubmission>

const makeStreams = () => {
  const stdout = new PassThrough()
  const stdin = new PassThrough()
  const stderr = new PassThrough()

  Object.assign(stdout, { columns: 80, isTTY: false, rows: 20 })
  Object.assign(stdin, { isTTY: false })
  Object.assign(stderr, { isTTY: false })
  stdout.on('data', () => {})

  return { stderr, stdin, stdout }
}

function Harness({ expose, opts }: { expose: React.MutableRefObject<SubmissionApi | null>; opts: UseSubmissionOptions }) {
  const submission = useSubmission(opts)

  useImperativeHandle(expose, () => submission)

  return null
}

describe('useSubmission local setup commands', () => {
  beforeEach(() => {
    resetUiState()
  })

  it('routes bare setup through the slash handler before a session exists', async () => {
    const expose = { current: null as SubmissionApi | null }
    const opts = buildOpts()
    const streams = makeStreams()

    const instance = renderSync(React.createElement(Harness, { expose, opts }), {
      patchConsole: false,
      stderr: streams.stderr as NodeJS.WriteStream,
      stdin: streams.stdin as NodeJS.ReadStream,
      stdout: streams.stdout as NodeJS.WriteStream
    })

    try {
      expose.current!.dispatchSubmission('setup')

      expect(opts.slashRef.current).toHaveBeenCalledWith('/setup')
      expect(opts.sys).not.toHaveBeenCalledWith('session not ready yet')
      expect(opts.gw.request).not.toHaveBeenCalled()
    } finally {
      instance.unmount()
      instance.cleanup()
    }
  })

  it('routes bare doctor through the slash handler before a session exists', async () => {
    const expose = { current: null as SubmissionApi | null }
    const opts = buildOpts()
    const streams = makeStreams()

    const instance = renderSync(React.createElement(Harness, { expose, opts }), {
      patchConsole: false,
      stderr: streams.stderr as NodeJS.WriteStream,
      stdin: streams.stdin as NodeJS.ReadStream,
      stdout: streams.stdout as NodeJS.WriteStream
    })

    try {
      expose.current!.dispatchSubmission('doctor')

      expect(opts.slashRef.current).toHaveBeenCalledWith('/doctor')
      expect(opts.sys).not.toHaveBeenCalledWith('session not ready yet')
      expect(opts.gw.request).not.toHaveBeenCalled()
    } finally {
      instance.unmount()
      instance.cleanup()
    }
  })
})

const buildOpts = (): UseSubmissionOptions => ({
  appendMessage: vi.fn(),
  composerActions: {
    clearIn: vi.fn(),
    dequeue: vi.fn(),
    enqueue: vi.fn(),
    handleTextPaste: vi.fn(),
    openEditor: vi.fn(),
    pushHistory: vi.fn(),
    removeQueue: vi.fn(),
    replaceQueue: vi.fn(),
    setCompIdx: vi.fn(),
    setHistoryDraft: vi.fn(),
    setHistoryIdx: vi.fn(),
    setInput: vi.fn(),
    setInputBuf: vi.fn(),
    setPasteSnips: vi.fn(),
    setQueueEdit: vi.fn(),
    syncQueue: vi.fn()
  },
  composerRefs: {
    historyDraftRef: { current: '' },
    historyRef: { current: [] },
    queueEditRef: { current: null },
    queueRef: { current: [] },
    submitRef: { current: vi.fn() }
  },
  composerState: {
    compIdx: 0,
    compReplace: 0,
    completions: [],
    historyIdx: null,
    input: '',
    inputBuf: [],
    pasteSnips: [],
    queueEditIdx: null,
    queuedDisplay: []
  },
  gw: {
    getLogTail: vi.fn(() => ''),
    kill: vi.fn(),
    request: vi.fn(() => Promise.resolve({}))
  },
  isExactSlashCommand: vi.fn(() => false),
  maybeGoodVibes: vi.fn(),
  setLastUserMsg: vi.fn(),
  slashRef: { current: vi.fn(() => true) },
  sys: vi.fn()
})
