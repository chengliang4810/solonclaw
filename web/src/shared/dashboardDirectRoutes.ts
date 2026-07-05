const DIRECT_ROUTE_HASHES: Record<string, string> = {
  '/status': '#/solonclaw/diagnostics',
  '/diagnostics': '#/solonclaw/diagnostics',
  '/login': '#/',
  '/chat': '#/solonclaw/chat',
  '/sessions': '#/solonclaw/runs',
  '/analytics': '#/solonclaw/usage',
  '/models': '#/solonclaw/models',
  '/memory': '#/solonclaw/persona/journal',
  '/logs': '#/solonclaw/logs',
  '/gateways': '#/solonclaw/gateways',
  '/channels': '#/solonclaw/channels',
  '/agents': '#/solonclaw/agents',
  '/files': '#/solonclaw/files',
  '/workspace': '#/solonclaw/files',
  '/tui-runtime': '#/solonclaw/tui-runtime',
  '/curator': '#/solonclaw/curator',
  '/mcp': '#/solonclaw/mcp',
  '/cron': '#/solonclaw/jobs',
  '/skills': '#/solonclaw/skills',
  '/config': '#/solonclaw/settings',
  '/env': '#/solonclaw/settings',
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
