import type { ApprovalReq } from '../types.js'

export type ApprovalRespondParams = Record<string, unknown> & {
  approval_id?: string
  choice: string
  session_id?: string
}

export type ApprovalRespondChoice =
  | string
  | {
      readonly approvalId?: string
      readonly choice: string
    }

const resolveApprovalChoice = (value: ApprovalRespondChoice) =>
  typeof value === 'string' ? { choice: value } : value

export function buildApprovalRespondParams(
  approval: null | Pick<ApprovalReq, 'approvalId' | 'sessionId'> | undefined,
  value: ApprovalRespondChoice,
  currentSessionId?: null | string
): ApprovalRespondParams {
  const resolved = resolveApprovalChoice(value)

  return {
    approval_id: resolved.approvalId || approval?.approvalId,
    choice: resolved.choice,
    session_id: approval?.sessionId ?? currentSessionId ?? undefined
  }
}
