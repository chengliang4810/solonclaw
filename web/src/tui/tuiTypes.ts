export type ConnectionState = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'closed'

export type BusyPolicy = 'queue' | 'steer' | 'interrupt'

export type HistoryRole = 'user' | 'assistant' | 'system' | 'tool' | 'approval'

export type RunTimelineKind =
  | 'run'
  | 'event'
  | 'tool'
  | 'approval'
  | 'subagent'
  | 'recovery'
  | 'control'
  | 'checkpoint'

export interface VirtualHistoryItem {
  id: string
  role: HistoryRole
  text: string
  createdAt: number
  sessionId?: string
  model?: string
  status?: 'pending' | 'streaming' | 'done' | 'error'
  urls?: string[]
}

export interface TuiSession {
  id: string
  title: string
  cwd: string
  model: string
  branch?: string
  createdAt: number
  active: boolean
}

export interface TuiSessionControls {
  sessionId: string
  title: string
  branchName: string
  parentSessionId?: string
  compressed: boolean
  compressedSummary?: string
  controls: string[]
  updatedAt: number
}

export interface TuiApproval {
  id: string
  approvalId?: string
  selector?: string
  title: string
  command: string
  reason: string
  risk: 'low' | 'medium' | 'high'
  createdAt: number
  expiresAt?: number
  status: 'pending' | 'approved' | 'denied'
  toolName?: string
  choice?: string
  permanentAllowed?: boolean
}

export interface TuiRunTimelineItem {
  id: string
  kind: RunTimelineKind
  title: string
  detail: string
  status?: string
  severity?: 'info' | 'warn' | 'error'
  runId?: string
  sessionId?: string
  createdAt: number
  seq?: number
}

export type TuiIntegrationKind = 'cron' | 'kanban' | 'mcp' | 'acp'

export interface TuiIntegrationItem {
  id: string
  title: string
  status: string
  meta?: string
  time?: number | string
  enabled?: boolean
  toolCount?: number
}

export interface TuiIntegrationSnapshot {
  kind: TuiIntegrationKind
  title: string
  status: string
  available: boolean
  summary: string
  metrics: Record<string, number | string | boolean | null>
  items: TuiIntegrationItem[]
  updatedAt: number
  error?: string
}

export interface TuiCommand {
  name: string
  description: string
  hotkey?: string
}

export interface TuiModelOption {
  id: string
  label: string
  provider: string
  context: string
}

export interface TuiEvent {
  type:
    | 'connection'
    | 'session'
    | 'history'
    | 'stream'
    | 'busy'
    | 'approval'
    | 'model'
    | 'command'
    | 'integration'
    | 'sessionControl'
    | 'notice'
    | 'clear'
  payload?: unknown
}

export interface TuiState {
  connection: ConnectionState
  reconnectAttempt: number
  busy: boolean
  busyPolicy: BusyPolicy
  activeSessionId: string
  activeModelId: string
  sessions: TuiSession[]
  history: VirtualHistoryItem[]
  approvals: TuiApproval[]
  timeline: TuiRunTimelineItem[]
  integrations: Record<string, TuiIntegrationSnapshot>
  sessionControls: Record<string, TuiSessionControls>
  queuedInputs: string[]
  commands: TuiCommand[]
  models: TuiModelOption[]
  recentCommands: string[]
  recentModels: string[]
  recentSessions: string[]
  lastSeqBySession: Record<string, number>
  lastError?: string
}

export interface JsonRpcRequest {
  id: string
  method: string
  params?: unknown
  sessionId?: string
}

export interface JsonRpcNotification {
  method: string
  params?: unknown
  sessionId?: string
}

export interface JsonRpcResponse {
  type?: string
  id?: string
  sessionId?: string
  seq?: number
  payload?: unknown
}
