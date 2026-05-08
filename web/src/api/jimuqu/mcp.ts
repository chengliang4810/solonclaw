import { request } from '../client'

export interface McpServer {
  server_id: string
  name: string
  transport: string
  endpoint?: string
  command?: string
  args?: unknown
  auth?: unknown
  oauth?: Record<string, unknown>
  capabilities?: unknown
  status: string
  tools?: unknown
  last_tools_hash?: string
  last_error?: string
  enabled: boolean
  created_at: number
  updated_at: number
  last_checked_at: number
  last_tools_changed_at?: number
}

export interface McpActionResult {
  server_id: string
  action?: string
  status?: string
  tools_hash?: string
  previous_tool_count?: number
  current_tool_count?: number
  tool_count?: number
  tool_changed_notification?: boolean
  added_tools?: string[]
  removed_tools?: string[]
  schema_sanitizer?: string
  error?: string
  security?: Record<string, unknown>
}

export interface McpReloadAllResult {
  enabled: boolean
  tool_count: number
  changed_servers: string[]
  unchanged_servers: string[]
  tool_changed_notification: boolean
  changed_count: number
  unchanged_count: number
  server_count: number
}

export interface McpOAuthStatus {
  server_id: string
  enabled: boolean
  provider?: string
  auth_type?: string
  status: string
  authenticated: boolean
  has_access_token: boolean
  has_refresh_token: boolean
  has_client_secret: boolean
  expires_at?: number
  expires_in_seconds?: number
  scopes?: unknown
  oauth?: Record<string, unknown>
}

export interface McpOAuthBeginResult {
  server_id: string
  status: string
  state: string
  authorization_url: string
  code_challenge_method: string
  redirect_uri: string
  scope?: string
  oauth?: Record<string, unknown>
}

export async function fetchMcpServers(): Promise<{ enabled: boolean; servers: McpServer[] }> {
  return request('/api/jimuqu/mcp')
}

export async function saveMcpServer(data: Record<string, unknown>) {
  return request('/api/jimuqu/mcp', {
    method: 'POST',
    body: JSON.stringify(data),
  })
}

export async function reloadAllMcpServers(): Promise<McpReloadAllResult> {
  return request('/api/jimuqu/mcp/reload', { method: 'POST' })
}

export async function checkMcpServer(serverId: string): Promise<McpActionResult> {
  return request(`/api/jimuqu/mcp/${serverId}/check`, { method: 'POST' })
}

export async function connectMcpServer(serverId: string): Promise<McpActionResult> {
  return request(`/api/jimuqu/mcp/${serverId}/connect`, { method: 'POST' })
}

export async function reloadMcpServer(serverId: string): Promise<McpActionResult> {
  return request(`/api/jimuqu/mcp/${serverId}/reload`, { method: 'POST' })
}

export async function refreshMcpTools(serverId: string): Promise<McpActionResult> {
  return request(`/api/jimuqu/mcp/${serverId}/tools/refresh`, { method: 'POST' })
}

export async function fetchMcpOAuthStatus(serverId: string): Promise<McpOAuthStatus> {
  return request(`/api/jimuqu/mcp/${serverId}/oauth/status`)
}

export async function beginMcpOAuth(serverId: string, data: Record<string, unknown>): Promise<McpOAuthBeginResult> {
  return request(`/api/jimuqu/mcp/${serverId}/oauth/begin`, {
    method: 'POST',
    body: JSON.stringify(data),
  })
}

export async function completeMcpOAuth(serverId: string, data: Record<string, unknown>) {
  return request(`/api/jimuqu/mcp/${serverId}/oauth/callback`, {
    method: 'POST',
    body: JSON.stringify(data),
  })
}

export async function refreshMcpOAuth(serverId: string) {
  return request(`/api/jimuqu/mcp/${serverId}/oauth/refresh`, { method: 'POST' })
}

export async function handleMcpOAuth401(serverId: string) {
  return request(`/api/jimuqu/mcp/${serverId}/oauth/handle-401`, { method: 'POST' })
}

export async function clearMcpOAuth(serverId: string) {
  return request(`/api/jimuqu/mcp/${serverId}/oauth/clear`, { method: 'POST' })
}

export async function deleteMcpServer(serverId: string) {
  return request(`/api/jimuqu/mcp/${serverId}`, { method: 'DELETE' })
}
