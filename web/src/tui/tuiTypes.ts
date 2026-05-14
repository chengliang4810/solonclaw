export type ConnectionState = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'closed'

export type BusyPolicy = 'queue' | 'steer' | 'interrupt'

export type HistoryRole = 'user' | 'assistant' | 'system' | 'tool' | 'approval'

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

export interface TuiApproval {
  id: string
  title: string
  command: string
  reason: string
  risk: 'low' | 'medium' | 'high'
  createdAt: number
  expiresAt?: number
  status: 'pending' | 'approved' | 'denied'
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
  queuedInputs: string[]
  commands: TuiCommand[]
  models: TuiModelOption[]
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
