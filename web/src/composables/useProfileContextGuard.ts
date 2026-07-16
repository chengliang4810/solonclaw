import { currentProfileContextVersion, onProfileContextChange } from '@/shared/profileContext'
import { onScopeDispose } from 'vue'

/** 为 Profile 作用域 Store 提供同步重置和迟到异步结果校验。 */
export function useProfileContextGuard(reset: () => void) {
  let version = currentProfileContextVersion()
  const unsubscribe = onProfileContextChange(nextVersion => {
    version = nextVersion
    reset()
  })
  onScopeDispose(() => {
    unsubscribe()
    reset()
  })

  return {
    /** 捕获异步操作发起时的 Profile 代际。 */
    capture: (): number => version,
    /** 判断异步结果是否仍属于当前 Profile。 */
    isCurrent: (captured: number): boolean => captured === version,
  }
}
