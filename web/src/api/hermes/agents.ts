import { request } from '../client'

export interface SolonClawAgentRunSummary {
  run_id: string
  session_id: string
  status: string
  model?: string
  started_at?: number
  finished_at?: number
}

export interface SolonClawAgent {
  name: string
  display_name: string
  description?: string
  default_agent: boolean
  readonly: boolean
  enabled: boolean
  active: boolean
  default_model?: string
  role_prompt?: string
  allowed_tools_json?: string
  skills_json?: string
  memory?: string
  workspace_path?: string
  skills_path?: string
  cache_path?: string
  running_runs?: number
  recent_runs?: SolonClawAgentRunSummary[]
  last_used_at?: number
  updated_at?: number
}

export interface SolonClawAgentsResponse {
  agents: SolonClawAgent[]
  active_agent_name: string
}

export interface AgentMutationPayload {
  name?: string
  display_name?: string
  description?: string
  role_prompt?: string
  default_model?: string
  allowed_tools_json?: string
  skills_json?: string
  memory?: string
  enabled?: boolean
}

function withSession(path: string, sessionId?: string | null): string {
  if (!sessionId) return path
  const params = new URLSearchParams()
  params.set('session_id', sessionId)
  return `${path}?${params.toString()}`
}

export async function fetchAgents(sessionId?: string | null): Promise<SolonClawAgentsResponse> {
  return request<SolonClawAgentsResponse>(withSession('/api/agents', sessionId))
}

export async function fetchAgent(name: string, sessionId?: string | null): Promise<SolonClawAgent> {
  return request<SolonClawAgent>(withSession(`/api/agents/${encodeURIComponent(name)}`, sessionId))
}

export async function createAgent(payload: AgentMutationPayload): Promise<SolonClawAgent> {
  return request<SolonClawAgent>('/api/agents', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export async function updateAgent(name: string, payload: AgentMutationPayload): Promise<SolonClawAgent> {
  return request<SolonClawAgent>(`/api/agents/${encodeURIComponent(name)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export async function deleteAgent(name: string): Promise<void> {
  await request(`/api/agents/${encodeURIComponent(name)}`, {
    method: 'DELETE',
  })
}

export async function activateAgent(name: string, sessionId: string): Promise<{ session_id: string; active_agent_name: string }> {
  return request<{ session_id: string; active_agent_name: string }>(`/api/agents/${encodeURIComponent(name)}/activate`, {
    method: 'POST',
    body: JSON.stringify({ session_id: sessionId }),
  })
}
