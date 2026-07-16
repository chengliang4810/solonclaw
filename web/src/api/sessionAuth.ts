import { clearChatCacheStorage, newCacheScopeId } from '../shared/chatCacheScope.ts'

declare global {
  interface Window {
    __LOGIN_TOKEN__?: string
  }
}

const DEFAULT_BASE_URL = ''
const TOKEN_KEY = 'solonclaw_api_key'
const SERVER_URL_KEY = 'solonclaw_server_url'
const AUTH_SCOPE_KEY = 'solonclaw_auth_scope_v1'
const authContextListeners = new Set<() => void>()

/** 注册认证上下文变化监听器，供内存 Store 同步清理旧主体数据。 */
export function onAuthContextChange(listener: () => void): () => void {
  authContextListeners.add(listener)
  return () => authContextListeners.delete(listener)
}

/** 先通知内存状态停止活动任务，再删除所有不再可信的聊天缓存。 */
function invalidateAuthContext(): void {
  for (const listener of authContextListeners) {
    try {
      listener()
    } catch (error) {
      console.error('Failed to reset auth-scoped state:', error)
    }
  }
  clearChatCacheStorage()
}

/** 其他标签页切换账号或服务端时，立即废弃本页仍在运行的旧主体状态。 */
function handleAuthStorageChange(event: StorageEvent): void {
  if (event.key === TOKEN_KEY || event.key === SERVER_URL_KEY || event.key === null) {
    invalidateAuthContext()
  }
}

if (typeof window.addEventListener === 'function') {
  window.addEventListener('storage', handleAuthStorageChange)
}

function isLoopbackHost(hostname: string): boolean {
  const host = hostname.toLowerCase()
  return host === 'localhost' || host === '::1' || host === '[::1]' || host.startsWith('127.')
}

function isLoopbackUrl(value: string): boolean {
  try {
    return isLoopbackHost(new URL(value).hostname)
  } catch {
    return false
  }
}

export function getBaseUrlValue(): string {
  const stored = localStorage.getItem(SERVER_URL_KEY) || DEFAULT_BASE_URL
  if (stored && isLoopbackHost(window.location.hostname) && isLoopbackUrl(stored)) {
    return DEFAULT_BASE_URL
  }
  return stored
}

export function getInjectedToken(): string {
  return window.__LOGIN_TOKEN__ || ''
}

export function getApiKey(): string {
  return localStorage.getItem(TOKEN_KEY) || getInjectedToken()
}

/** 返回当前认证主体的非秘密缓存作用域；无认证或存储不可用时禁用聊天缓存。 */
export function getAuthScopeId(): string {
  if (!getApiKey()) return ''
  try {
    const existing = localStorage.getItem(AUTH_SCOPE_KEY)
    if (existing) return existing
    const created = newCacheScopeId()
    localStorage.setItem(AUTH_SCOPE_KEY, created)
    return localStorage.getItem(AUTH_SCOPE_KEY) || created
  } catch {
    return ''
  }
}

export function setServerUrl(url: string) {
  const previous = getBaseUrlValue()
  localStorage.setItem(SERVER_URL_KEY, url)
  if (getBaseUrlValue() !== previous) invalidateAuthContext()
}

export function setApiKey(key: string) {
  const changed = getApiKey() !== key
  localStorage.setItem(TOKEN_KEY, key)
  if (changed) {
    try {
      localStorage.removeItem(AUTH_SCOPE_KEY)
      localStorage.setItem(AUTH_SCOPE_KEY, newCacheScopeId())
    } catch {
      // 缓存作用域是辅助数据；写入失败时清旧状态并让后续读取自然降级为无缓存。
    } finally {
      invalidateAuthContext()
    }
  } else {
    getAuthScopeId()
  }
}

export function clearApiKey() {
  invalidateAuthContext()
  try {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(AUTH_SCOPE_KEY)
  } finally {
    window.__LOGIN_TOKEN__ = ''
  }
}

export function hasApiKey(): boolean {
  return !!getApiKey()
}
