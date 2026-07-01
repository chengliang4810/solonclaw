const DIRECT_ROUTE_HASHES: Record<string, string> = {
  '/chat': '#/solonclaw/chat',
  '/models': '#/solonclaw/models',
  '/logs': '#/solonclaw/logs',
  '/gateways': '#/solonclaw/gateways',
  '/channels': '#/solonclaw/channels',
  '/agents': '#/solonclaw/agents',
  '/files': '#/solonclaw/files',
  '/cron': '#/solonclaw/jobs',
  '/skills': '#/solonclaw/skills',
  '/config': '#/solonclaw/settings',
}

export function dashboardHashRouteForPath(pathname: string): string {
  const path = normalizePath(pathname)
  if (path === '/solonclaw') {
    return '#/solonclaw/chat'
  }
  if (path.startsWith('/solonclaw/')) {
    return `#${path}`
  }
  return DIRECT_ROUTE_HASHES[path] || ''
}

function normalizePath(pathname: string): string {
  const path = pathname || '/'
  return path.length > 1 ? path.replace(/\/+$/, '') : path
}
