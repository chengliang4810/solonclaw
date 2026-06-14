export interface SessionSelectionCandidate {
  id: string
}

// 会话恢复入口的优先级：明确 URL 会话 > 已保存活跃会话 > 最新会话。
// 只接受当前列表里存在的会话，避免坏链接把界面切到空状态。
export function selectSessionId(
  sessions: SessionSelectionCandidate[],
  preferredSessionId?: string | null,
  savedSessionId?: string | null,
): string | undefined {
  if (preferredSessionId && sessions.some(s => s.id === preferredSessionId)) {
    return preferredSessionId
  }
  if (savedSessionId && sessions.some(s => s.id === savedSessionId)) {
    return savedSessionId
  }
  return sessions[0]?.id
}
