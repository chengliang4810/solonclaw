let profileContextName = 'default'
let profileContextVersion = 0
const profileContextListeners = new Set<(version: number) => void>()

/** 返回当前 Profile 上下文代际。 */
export function currentProfileContextVersion(): number {
  return profileContextVersion
}

/** 注册 Profile 上下文变化监听器。 */
export function onProfileContextChange(listener: (version: number) => void): () => void {
  profileContextListeners.add(listener)
  return () => profileContextListeners.delete(listener)
}

/** 在实际管理目标变化时同步作废所有旧 Profile 状态。 */
export function updateProfileContext(name: string): void {
  const normalized = name.trim() || 'default'
  if (normalized === profileContextName) return
  profileContextName = normalized
  profileContextVersion += 1
  for (const listener of profileContextListeners) {
    try {
      listener(profileContextVersion)
    } catch (error) {
      console.error('Failed to reset Profile-scoped state:', error)
    }
  }
}
