export interface ChatContextUsageInput {
  lastTotalTokens?: number | null
  inputTokens?: number | null
  outputTokens?: number | null
}

export interface ChatContextUsage {
  usedTokens: number
  remainingTokens: number
  usagePercent: number
}

function nonNegative(value?: number | null): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) return 0
  return Math.floor(value)
}

export function computeChatContextUsage(
  session: ChatContextUsageInput | null | undefined,
  contextLength: number,
): ChatContextUsage {
  const limit = nonNegative(contextLength)
  const lastTotal = nonNegative(session?.lastTotalTokens)
  const cumulative = nonNegative(session?.inputTokens) + nonNegative(session?.outputTokens)
  const usedTokens = lastTotal > 0 ? lastTotal : cumulative
  const remainingTokens = Math.max(limit - usedTokens, 0)
  const usagePercent = limit > 0 ? Math.min((usedTokens / limit) * 100, 100) : 0

  return {
    usedTokens,
    remainingTokens,
    usagePercent,
  }
}
