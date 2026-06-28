import { describe, expect, it } from 'vitest'

import { shellExecSettledUiState } from '../app/useSubmission.js'

describe('shellExecSettledUiState', () => {
  it('keeps the terminal busy while a shell command is waiting for approval', () => {
    expect(shellExecSettledUiState(true)).toEqual({ busy: true, status: '需要审批' })
  })

  it('returns the terminal to ready after normal shell command completion', () => {
    expect(shellExecSettledUiState(false)).toEqual({ busy: false, status: 'ready' })
  })
})
