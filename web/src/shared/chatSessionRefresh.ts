export interface RefreshSessionCandidate {
  id: string
  messages?: unknown[]
}

export function mergeRefreshedSessions<T extends RefreshSessionCandidate>(
  current: T[],
  fresh: T[],
  isLocalSessionActive: (id: string) => boolean,
): T[] {
  const freshIds = new Set(fresh.map(s => s.id))
  const messagesById = new Map(current.map(s => [s.id, s.messages]))
  const mergedFresh = fresh.map(s => {
    const messages = messagesById.get(s.id)
    return messages?.length ? { ...s, messages } : s
  })
  const activeLocal = current.filter(s => !freshIds.has(s.id) && isLocalSessionActive(s.id))
  return [...activeLocal, ...mergedFresh]
}
