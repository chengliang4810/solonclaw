import assert from 'node:assert/strict'
import { computeChatContextUsage } from '../src/shared/chatContextUsage.ts'

assert.deepEqual(
  computeChatContextUsage(
    {
      inputTokens: 2485026,
      outputTokens: 49362,
      lastTotalTokens: 30933,
    },
    128000,
  ),
  {
    usedTokens: 30933,
    remainingTokens: 97067,
    usagePercent: 24.16640625,
  },
)

assert.deepEqual(
  computeChatContextUsage(
    {
      inputTokens: 2485026,
      outputTokens: 49362,
    },
    128000,
  ),
  {
    usedTokens: 2534388,
    remainingTokens: 0,
    usagePercent: 100,
  },
)

assert.deepEqual(
  computeChatContextUsage(
    {
      inputTokens: -10,
      outputTokens: Number.NaN,
      lastTotalTokens: 0,
    },
    128000,
  ),
  {
    usedTokens: 0,
    remainingTokens: 128000,
    usagePercent: 0,
  },
)
