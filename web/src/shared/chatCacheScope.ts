const CHAT_CACHE_OWNER_KEY = 'solonclaw_chat_cache_owner_v3'
const CHAT_CACHE_PREFIX = 'solonclaw_chat_cache_v3_'
const LEGACY_CHAT_CACHE_PREFIXES = [
  'solonclaw_active_session_v2',
  'solonclaw_sessions_cache_v2',
  'solonclaw_session_msgs_v2_',
  'solonclaw_in_flight_v2_',
]

/** 浏览器聊天缓存所属的认证上下文。 */
interface ChatCacheOwner {
  /** 实际 API 服务端。 */
  server: string
  /** 认证模块生成的非秘密主体作用域。 */
  authScopeId: string
  /** 不透明缓存命名空间。 */
  scopeId: string
}

/** 规范化实际 API 服务端，区分同源部署和远程 Dashboard。 */
function normalizeServer(baseUrl: string): string {
  try {
    return new URL(baseUrl || '/', window.location.origin).href
  } catch {
    return baseUrl.trim()
  }
}

/** 读取并校验已保存的缓存所有者。 */
function readOwner(): ChatCacheOwner | null {
  try {
    const value = JSON.parse(localStorage.getItem(CHAT_CACHE_OWNER_KEY) || 'null') as Partial<ChatCacheOwner> | null
    return value
      && typeof value.server === 'string'
      && typeof value.authScopeId === 'string'
      && value.authScopeId.length > 0
      && typeof value.scopeId === 'string'
      && value.scopeId.length > 0
      ? value as ChatCacheOwner
      : null
  } catch {
    return null
  }
}

/** 创建不包含访问令牌的缓存命名空间标识。 */
export function newCacheScopeId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID()
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
}

/** 保存缓存所有者；浏览器禁用存储时返回失败并禁用缓存。 */
function saveOwner(owner: ChatCacheOwner): boolean {
  try {
    localStorage.setItem(CHAT_CACHE_OWNER_KEY, JSON.stringify(owner))
    return true
  } catch {
    return false
  }
}

/** 列出当前浏览器中的聊天缓存键。 */
function chatCacheKeys(includeOwner: boolean): string[] {
  const keys: string[] = []
  try {
    for (let index = 0; index < localStorage.length; index += 1) {
      const key = localStorage.key(index)
      if (!key) continue
      if ((includeOwner && key === CHAT_CACHE_OWNER_KEY)
        || key.startsWith(CHAT_CACHE_PREFIX)
        || LEGACY_CHAT_CACHE_PREFIXES.some(prefix => key.startsWith(prefix))) {
        keys.push(key)
      }
    }
  } catch {
    return []
  }
  return keys
}

/** 删除全部主体和服务端的聊天缓存及所有者记录。 */
export function clearChatCacheStorage(): void {
  for (const key of chatCacheKeys(true)) {
    try {
      localStorage.removeItem(key)
    } catch {
      // 浏览器禁用存储时无需继续处理，内存态仍会由认证事件清空。
    }
  }
}

/** 配额不足时只删除缓存数据，保留当前认证命名空间。 */
export function recoverChatCacheQuota(): void {
  for (const key of chatCacheKeys(false)) {
    try {
      localStorage.removeItem(key)
    } catch {
      // 缓存是尽力写入，删除失败时由后续写入自然降级。
    }
  }
}

/**
 * 返回当前认证主体和服务端专属的缓存键。
 *
 * <p>认证模块在令牌变化时生成新的非秘密作用域；缓存元数据和数据键均不保存访问令牌。
 */
export function chatCacheKey(
  baseUrl: string,
  authScopeId: string,
  category: string,
  suffix = '',
): string | null {
  if (!authScopeId) return null

  const server = normalizeServer(baseUrl)
  let owner = readOwner()
  if (!owner || owner.server !== server || owner.authScopeId !== authScopeId) {
    clearChatCacheStorage()
    owner = { server, authScopeId, scopeId: newCacheScopeId() }
    if (!saveOwner(owner)) return null
  }

  const tail = suffix ? `_${encodeURIComponent(suffix)}` : ''
  return `${CHAT_CACHE_PREFIX}${owner.scopeId}_${category}${tail}`
}
