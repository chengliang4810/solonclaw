const DIRECT_ROUTE_HASHES: Record<string, string> = {
  '/status': '#/diagnostics',
  '/diagnostics': '#/diagnostics',
  '/login': '#/',
  '/chat': '#/chat',
  '/sessions': '#/runs',
  '/analytics': '#/usage',
  '/models': '#/models',
  '/agents': '#/agents',
  '/memory': '#/persona/journal',
  '/logs': '#/logs',
  '/gateways': '#/gateways',
  '/profiles': '#/profiles',
  '/profiles/new': '#/profiles/new',
  '/channels': '#/channels',
  '/files': '#/files',
  '/workspace': '#/files',
  '/tui-runtime': '#/tui-runtime',
  '/curator': '#/curator',
  '/mcp': '#/mcp',
  '/cron': '#/jobs',
  '/skills': '#/skills',
  '/config': '#/settings',
  '/env': '#/settings',
}

export function dashboardHashRouteForPath(pathname: string): string {
  const path = normalizePath(pathname)
  return DIRECT_ROUTE_HASHES[path] || ''
}

function normalizePath(pathname: string): string {
  const path = pathname || '/'
  return path.length > 1 ? path.replace(/\/+$/, '') : path
}
