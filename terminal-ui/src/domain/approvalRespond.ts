import type { ApprovalReq } from '../types.js'

export type ApprovalRespondParams = Record<string, unknown> & {
  approval_id?: string
  choice: string
  session_id?: string
}

export function buildApprovalRespondParams(
  approval: null | Pick<ApprovalReq, 'approvalId' | 'sessionId'> | undefined,
  choice: string,
  currentSessionId?: null | string
): ApprovalRespondParams {
  return {
    approval_id: approval?.approvalId,
    choice,
    session_id: approval?.sessionId ?? currentSessionId ?? undefined
  }
}
