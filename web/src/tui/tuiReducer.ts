import type { TuiApproval, TuiCommand, TuiEvent, TuiModelOption, TuiRunTimelineItem, TuiSession, TuiState, VirtualHistoryItem } from './tuiTypes'
import { extractSafeUrls } from './tuiSafety'

const HISTORY_LIMIT = 600
const TIMELINE_LIMIT = 500

export const defaultCommands: TuiCommand[] = [
  { name: '/new', description: '新建会话', hotkey: 'Ctrl+N' },
  { name: '/retry', description: '重试上一轮' },
  { name: '/undo', description: '撤销上一轮' },
  { name: '/resume', description: '恢复会话' },
  { name: '/model', description: '切换模型', hotkey: 'Ctrl+M' },
  { name: '/approve list', description: '查看待审批项' },
  { name: '/busy', description: '查看或调整忙碌输入策略' },
  { name: '/status', description: '查看运行状态' },
]

export const defaultModels: TuiModelOption[] = [
  { id: 'default', label: '默认模型', provider: 'runtime', context: '跟随运行时配置' },
  { id: 'fast', label: '快速模型', provider: 'runtime', context: '适合短指令和状态查询' },
  { id: 'reasoning', label: '推理模型', provider: 'runtime', context: '适合复杂任务拆解' },
]

const now = Date.now()

export const initialTuiState: TuiState = {
  connection: 'idle',
  reconnectAttempt: 0,
  busy: false,
  busyPolicy: 'queue',
  activeSessionId: 'local',
  activeModelId: 'default',
  sessions: [
    {
      id: 'local',
      title: '本地会话',
      cwd: 'workspace',
      model: 'default',
      createdAt: now,
      active: true,
    },
  ],
  history: [
    createHistoryItem('system', 'React 终端 UI 已就绪。连接可用时会通过 WebSocket JSON-RPC 同步事件，离线时保留本地虚拟历史。', 'local'),
  ],
  approvals: [],
  timeline: [],
  queuedInputs: [],
  commands: defaultCommands,
  models: defaultModels,
  recentCommands: [],
  recentModels: [],
  recentSessions: ['local'],
  lastSeqBySession: {},
}

export function tuiReducer(state: TuiState, event: TuiEvent): TuiState {
  switch (event.type) {
    case 'connection':
      return reduceConnection(state, event.payload)
    case 'session':
      return reduceSession(state, event.payload)
    case 'history':
      return appendHistory(state, normalizeHistory(event.payload, state.activeSessionId))
    case 'stream':
      return appendStream(state, event.payload)
    case 'busy':
      return reduceBusy(state, event.payload)
    case 'approval':
      return reduceApproval(state, event.payload)
    case 'model':
      return reduceModel(state, event.payload)
    case 'command':
      return reduceCommand(state, event.payload)
    case 'notice':
      return appendNotice(state, event.payload)
    case 'clear':
      return { ...state, history: [] }
    default:
      return state
  }
}

export function createHistoryItem(role: VirtualHistoryItem['role'], text: string, sessionId: string): VirtualHistoryItem {
  return {
    id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
    role,
    text,
    createdAt: Date.now(),
    sessionId,
    status: 'done',
    urls: extractSafeUrls(text),
  }
}

function reduceConnection(state: TuiState, payload: unknown): TuiState {
  const data = objectPayload(payload)
  return {
    ...state,
    connection: stringValue(data.state, state.connection) as TuiState['connection'],
    reconnectAttempt: numberValue(data.reconnectAttempt, state.reconnectAttempt),
    lastError: typeof data.error === 'string' ? data.error : undefined,
  }
}

function reduceSession(state: TuiState, payload: unknown): TuiState {
  const data = objectPayload(payload)
  const sessions = Array.isArray(data.sessions)
    ? data.sessions.map((item) => normalizeSession(item)).filter(Boolean) as TuiSession[]
    : state.sessions

  const activeSessionId = stringValue(data.activeSessionId, state.activeSessionId)
  const mergedSessions = mergeSessions(state.sessions, sessions)
  return {
    ...state,
    activeSessionId,
    recentSessions: rememberRecent(state.recentSessions, activeSessionId),
    sessions: mergedSessions.map((session) => ({ ...session, active: session.id === activeSessionId })),
  }
}

function appendHistory(state: TuiState, item: VirtualHistoryItem): TuiState {
  return {
    ...state,
    history: [...state.history, item].slice(-HISTORY_LIMIT),
  }
}

function appendNotice(state: TuiState, payload: unknown): TuiState {
  const data = objectPayload(payload)
  const timeline = normalizeTimeline(data.timeline)
  if (timeline) {
    return appendTimeline(state, timeline)
  }
  const text = textFromPayload(data.text || payload)
  if (!text || text === '{}' || text === '{"text":""}') return state
  return appendHistory(state, createHistoryItem('system', text, state.activeSessionId))
}

function appendStream(state: TuiState, payload: unknown): TuiState {
  const data = objectPayload(payload)
  const text = textFromPayload(data.text)
  if (!text) return state
  const last = state.history[state.history.length - 1]
  if (last && last.role === 'assistant' && last.status === 'streaming') {
    const updated = { ...last, text: `${last.text}${text}`, urls: extractSafeUrls(`${last.text}${text}`) }
    return { ...state, history: [...state.history.slice(0, -1), updated] }
  }
  return appendHistory(state, { ...createHistoryItem('assistant', text, state.activeSessionId), status: 'streaming' })
}

function reduceBusy(state: TuiState, payload: unknown): TuiState {
  const data = objectPayload(payload)
  return {
    ...state,
    busy: typeof data.busy === 'boolean' ? data.busy : Boolean(data.running),
    busyPolicy: stringValue(data.policy, state.busyPolicy) as TuiState['busyPolicy'],
    queuedInputs: Array.isArray(data.queuedInputs) ? data.queuedInputs.filter((item): item is string => typeof item === 'string') : state.queuedInputs,
  }
}

function reduceApproval(state: TuiState, payload: unknown): TuiState {
  const data = objectPayload(payload)
  if (Array.isArray(data.approvals)) {
    const approvals = data.approvals.map((item) => normalizeApproval(item)).filter(Boolean) as TuiApproval[]
    return {
      ...state,
      approvals: Boolean(data.replace) ? approvals : mergeApprovals(state.approvals, approvals),
    }
  }
  const approval = normalizeApproval(data)
  if (!approval) return state
  const exists = state.approvals.some((item) => item.id === approval.id)
  return {
    ...state,
    approvals: exists
      ? state.approvals.map((item) => (item.id === approval.id ? { ...item, ...approval } : item))
      : [approval, ...state.approvals].slice(0, 20),
    timeline: appendTimelineItem(state.timeline, {
      id: `approval-${approval.id}-${approval.status}`,
      kind: 'approval',
      title: approval.title,
      detail: approval.command || approval.reason,
      status: approval.status,
      severity: approval.status === 'denied' ? 'warn' : approval.risk === 'high' ? 'error' : 'info',
      sessionId: state.activeSessionId,
      createdAt: approval.createdAt,
    }),
    history: exists ? state.history : [...state.history, createHistoryItem('approval', `${approval.title}\n${approval.command}`, state.activeSessionId)].slice(-HISTORY_LIMIT),
  }
}

function reduceModel(state: TuiState, payload: unknown): TuiState {
  const data = objectPayload(payload)
  const models = Array.isArray(data.models)
    ? data.models.map((item) => normalizeModel(item)).filter(Boolean) as TuiModelOption[]
    : state.models
  const activeModelId = stringValue(data.activeModelId, state.activeModelId)
  return {
    ...state,
    activeModelId,
    models,
    recentModels: activeModelId ? rememberRecent(state.recentModels, activeModelId) : state.recentModels,
    sessions: state.sessions.map((session) =>
      session.id === state.activeSessionId ? { ...session, model: activeModelId } : session,
    ),
  }
}

function reduceCommand(state: TuiState, payload: unknown): TuiState {
  const data = objectPayload(payload)
  const commands = Array.isArray(data.commands)
    ? data.commands.map((item) => normalizeCommand(item)).filter(Boolean) as TuiCommand[]
    : state.commands
  const recent = typeof data.recentCommand === 'string' ? rememberRecent(state.recentCommands, data.recentCommand) : state.recentCommands
  return { ...state, commands, recentCommands: recent }
}

function normalizeHistory(payload: unknown, fallbackSessionId: string): VirtualHistoryItem {
  const data = objectPayload(payload)
  const role = stringValue(data.role, 'assistant') as VirtualHistoryItem['role']
  const text = textFromPayload(data.text || data.content || payload)
  return {
    id: stringValue(data.id, `${Date.now()}-${Math.random().toString(16).slice(2)}`),
    role,
    text,
    createdAt: numberValue(data.createdAt, Date.now()),
    sessionId: stringValue(data.sessionId, fallbackSessionId),
    model: typeof data.model === 'string' ? data.model : undefined,
    status: stringValue(data.status, 'done') as VirtualHistoryItem['status'],
    urls: extractSafeUrls(text),
  }
}

function normalizeSession(payload: unknown): TuiSession | null {
  const data = objectPayload(payload)
  const id = stringValue(data.id, stringValue(data.session_id, ''))
  if (!id) return null
  return {
    id,
    title: stringValue(data.title, id),
    cwd: stringValue(data.cwd, 'workspace'),
    model: stringValue(data.model, 'default'),
    branch: stringValue(data.branch, stringValue(data.branch_name, '')) || undefined,
    createdAt: numberValue(data.createdAt, numberValue(data.started_at, Date.now())),
    active: Boolean(data.active),
  }
}

function normalizeApproval(payload: unknown): TuiApproval | null {
  const data = objectPayload(payload)
  const id = stringValue(data.id || data.selector || data.approvalId || data.approval_id, '')
  if (!id) return null
  return {
    id,
    approvalId: stringValue(data.approvalId || data.approval_id, '') || undefined,
    selector: stringValue(data.selector, '') || id,
    title: stringValue(data.title, '需要审批'),
    command: stringValue(data.command, ''),
    reason: stringValue(data.reason || data.description, '该操作需要人工确认'),
    risk: stringValue(data.risk, 'medium') as TuiApproval['risk'],
    createdAt: numberValue(data.createdAt || data.created_at, Date.now()),
    expiresAt: typeof data.expiresAt === 'number' ? data.expiresAt : typeof data.expires_at === 'number' ? data.expires_at : undefined,
    status: stringValue(data.status, 'pending') as TuiApproval['status'],
    toolName: stringValue(data.toolName || data.tool_name, '') || undefined,
    choice: stringValue(data.choice, '') || undefined,
    permanentAllowed: typeof data.permanent_allowed === 'boolean' ? data.permanent_allowed : undefined,
  }
}

function normalizeTimeline(payload: unknown): TuiRunTimelineItem | null {
  const data = objectPayload(payload)
  const id = stringValue(data.id, '')
  if (!id) return null
  return {
    id,
    kind: stringValue(data.kind, 'event') as TuiRunTimelineItem['kind'],
    title: stringValue(data.title, '运行事件'),
    detail: stringValue(data.detail, ''),
    status: stringValue(data.status, '') || undefined,
    severity: stringValue(data.severity, 'info') as TuiRunTimelineItem['severity'],
    runId: stringValue(data.runId, '') || undefined,
    sessionId: stringValue(data.sessionId, '') || undefined,
    createdAt: numberValue(data.createdAt, Date.now()),
    seq: numberValue(data.seq, 0) || undefined,
  }
}

function normalizeModel(payload: unknown): TuiModelOption | null {
  const data = objectPayload(payload)
  const id = stringValue(data.id, stringValue(data.model, stringValue(data.default_model, '')))
  if (!id) return null
  return {
    id,
    label: stringValue(data.label, id),
    provider: stringValue(data.provider, 'runtime'),
    context: stringValue(data.context, ''),
  }
}

function normalizeCommand(payload: unknown): TuiCommand | null {
  const data = objectPayload(payload)
  const name = stringValue(data.name, '')
  if (!name) return null
  return {
    name,
    description: stringValue(data.description, ''),
    hotkey: typeof data.hotkey === 'string' ? data.hotkey : undefined,
  }
}

function objectPayload(payload: unknown): Record<string, unknown> {
  return payload && typeof payload === 'object' ? payload as Record<string, unknown> : {}
}

function textFromPayload(payload: unknown): string {
  return typeof payload === 'string' ? payload : JSON.stringify(payload ?? '')
}

function stringValue(value: unknown, fallback: string): string {
  return typeof value === 'string' ? value : fallback
}

function numberValue(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function mergeSessions(existing: TuiSession[], incoming: TuiSession[]): TuiSession[] {
  const byId = new Map<string, TuiSession>()
  for (const session of existing) byId.set(session.id, session)
  for (const session of incoming) byId.set(session.id, { ...byId.get(session.id), ...session })
  return Array.from(byId.values())
}

function mergeApprovals(existing: TuiApproval[], incoming: TuiApproval[]): TuiApproval[] {
  const byId = new Map<string, TuiApproval>()
  for (const approval of existing) byId.set(approval.id, approval)
  for (const approval of incoming) byId.set(approval.id, { ...byId.get(approval.id), ...approval })
  return Array.from(byId.values()).slice(0, 50)
}

function appendTimeline(state: TuiState, item: TuiRunTimelineItem): TuiState {
  const timeline = appendTimelineItem(state.timeline, item)
  const sessionId = item.sessionId || state.activeSessionId
  const seq = item.seq || 0
  return {
    ...state,
    timeline,
    lastSeqBySession: seq > 0 ? { ...state.lastSeqBySession, [sessionId]: Math.max(state.lastSeqBySession[sessionId] || 0, seq) } : state.lastSeqBySession,
  }
}

function appendTimelineItem(existing: TuiRunTimelineItem[], item: TuiRunTimelineItem): TuiRunTimelineItem[] {
  if (existing.some((entry) => entry.id === item.id && entry.status === item.status && entry.seq === item.seq)) {
    return existing
  }
  return [...existing, item]
    .sort((left, right) => left.createdAt - right.createdAt)
    .slice(-TIMELINE_LIMIT)
}

function rememberRecent(existing: string[], value: string): string[] {
  if (!value) return existing
  return [value, ...existing.filter((item) => item !== value)].slice(0, 8)
}
