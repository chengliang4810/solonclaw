const PROFILE_SCOPED_API_PREFIXES = [
  '/api/chat',
  '/api/config',
  '/api/workspace-config',
  '/api/sessions',
  '/api/search',
  '/api/runs',
  '/api/checkpoints',
  '/api/workspace',
  '/api/solonclaw/download',
  '/api/skills',
  '/api/tools',
  '/api/mcp',
  '/api/model',
  '/api/models',
  '/api/providers',
  '/api/cron',
  '/api/media',
  '/api/logs',
  '/api/gateway',
  '/api/status',
] as const

export function isProfileScopedApiPath(pathname: string): boolean {
  return PROFILE_SCOPED_API_PREFIXES.some(prefix => pathname === prefix || pathname.startsWith(`${prefix}/`))
}

export function appendManagementProfile(path: string, profile: string): string {
  const target = profile.trim()
  if (!target) return path

  const absolute = /^[a-z][a-z0-9+.-]*:/i.test(path)
  const url = new URL(path, 'http://solonclaw.local')
  if (!isProfileScopedApiPath(url.pathname)) return path
  if (url.searchParams.has('profile')) return path

  url.searchParams.set('profile', target)
  return absolute ? url.toString() : `${url.pathname}${url.search}${url.hash}`
}

export function normalizeManagementProfile(name: string, currentProfile: string): string {
  const target = name.trim()
  return !target || target === currentProfile ? '' : target
}

/**
 * 生成浏览器内部使用的 Profile 会话复合标识。
 *
 * 服务端会话 ID 只在单个 Profile 内唯一；这里分别编码 Profile 和会话 ID，
 * 避免聚合列表中同名会话共享 Vue key、缓存、流状态或选中状态。
 */
export function profileSessionIdentity(sessionId: string, profile?: string): string {
  return `${encodeURIComponent(profile?.trim() || 'default')}:${encodeURIComponent(sessionId)}`
}
