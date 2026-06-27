import { request } from '../client'

export interface ConversationSummary {
  id: string
  source: string
  model: string
  title: string | null
  started_at: number
  ended_at: number | null
  last_active: number
  message_count: number
  tool_call_count: number
  input_tokens: number
  output_tokens: number
  cache_read_tokens: number
  cache_write_tokens: number
  reasoning_tokens: number
  provider: string | null
  preview: string
  is_active: boolean
  thread_session_count: number
}

export interface ConversationMessage {
  id: number | string
  session_id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: number
}

export interface ConversationDetail {
  session_id: string
  messages: ConversationMessage[]
  visible_count: number
  thread_session_count: number
}

interface DashboardSessionSummary {
  id: string
  source: string | null
  model: string | null
  title: string | null
  started_at: number
  ended_at: number | null
  last_active: number
  message_count: number
  tool_call_count: number
  input_tokens: number
  output_tokens: number
  cache_read_tokens?: number
  cache_write_tokens?: number
  reasoning_tokens?: number
  provider?: string | null
  preview?: string | null
  parent_session_id?: string | null
}

interface DashboardSessionDetail {
  messages: Array<{
    role: 'user' | 'assistant' | 'system' | 'tool'
    content: string | null
    timestamp?: number
  }>
}

function mapSummary(session: DashboardSessionSummary): ConversationSummary {
  return {
    id: session.id,
    source: session.source || 'local',
    model: session.model || '',
    title: session.title,
    started_at: session.started_at,
    ended_at: session.ended_at,
    last_active: session.last_active || session.started_at || 0,
    message_count: session.message_count || 0,
    tool_call_count: session.tool_call_count || 0,
    input_tokens: session.input_tokens || 0,
    output_tokens: session.output_tokens || 0,
    cache_read_tokens: session.cache_read_tokens || 0,
    cache_write_tokens: session.cache_write_tokens || 0,
    reasoning_tokens: session.reasoning_tokens || 0,
    provider: session.provider || null,
    preview: session.preview || '',
    is_active: !session.ended_at,
    thread_session_count: session.parent_session_id ? 2 : 1,
  }
}

export async function fetchConversationSummaries(params: { humanOnly?: boolean; source?: string; limit?: number } = {}): Promise<ConversationSummary[]> {
  const query = new URLSearchParams()
  query.set('limit', String(params.limit || 100))
  query.set('offset', '0')
  const res = await request<{ sessions: DashboardSessionSummary[] }>(`/api/sessions?${query}`)
  return (res.sessions || [])
    .map(mapSummary)
    .filter(session => !params.source || session.source === params.source)
}

export async function fetchConversationDetail(sessionId: string, params: { humanOnly?: boolean; source?: string } = {}): Promise<ConversationDetail> {
  const detail = await request<DashboardSessionDetail>(`/api/sessions/${encodeURIComponent(sessionId)}/messages`)
  const visibleMessages = (detail.messages || [])
    .filter(message => !params.humanOnly || message.role === 'user' || message.role === 'assistant')
    .filter(message => message.role === 'user' || message.role === 'assistant')
    .map((message, index) => ({
      id: index + 1,
      session_id: sessionId,
      role: message.role as 'user' | 'assistant',
      content: message.content || '',
      timestamp: message.timestamp || 0,
    }))
  return {
    session_id: sessionId,
    messages: visibleMessages,
    visible_count: visibleMessages.length,
    thread_session_count: 1,
  }
}
