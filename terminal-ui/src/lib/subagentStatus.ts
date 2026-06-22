import type { SubagentStatus } from '../types.js'

/**
 * 子代理状态同时来自实时网关事件和磁盘快照读取；集中白名单可以避免两条入口对未知状态的降级规则分叉。
 */
const KNOWN_SUBAGENT_STATUSES = new Set<SubagentStatus>([
  'completed',
  'error',
  'failed',
  'interrupted',
  'queued',
  'running',
  'timeout'
])

/**
 * 终态事件优先级最高；晚到的 start/progress 事件不能把已结束的子代理重新标成运行中。
 */
export const isTerminalSubagentStatus = (status: SubagentStatus) =>
  status === 'completed' ||
  status === 'error' ||
  status === 'failed' ||
  status === 'interrupted' ||
  status === 'timeout'

export const keepTerminalSubagentStatusElseRunning = (status: SubagentStatus): SubagentStatus =>
  isTerminalSubagentStatus(status) ? status : 'running'

export const normalizeSubagentStatus = (status: unknown, fallback: SubagentStatus): SubagentStatus => {
  if (typeof status !== 'string') {
    return fallback
  }

  const normalized = status.toLowerCase() as SubagentStatus

  return KNOWN_SUBAGENT_STATUSES.has(normalized) ? normalized : fallback
}
