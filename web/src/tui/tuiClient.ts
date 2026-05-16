import { getApiKey, getBaseUrlValue } from '@/api/client'
import type { JsonRpcNotification, JsonRpcRequest, JsonRpcResponse, TuiEvent } from './tuiTypes'

type EventHandler = (event: TuiEvent) => void

export class TuiJsonRpcClient {
  private ws: WebSocket | null = null
  private nextId = 1
  private reconnectAttempt = 0
  private closedByUser = false
  private reconnectTimer: number | null = null
  private readonly pending = new Map<string, { resolve: (value: unknown) => void; reject: (reason?: unknown) => void }>()
  private readonly onEvent: EventHandler
  private readonly endpoint: string

  constructor(
    onEvent: EventHandler,
    endpoint = '/api/jimuqu/tui',
  ) {
    this.onEvent = onEvent
    this.endpoint = endpoint
  }

  connect() {
    this.closedByUser = false
    this.emitConnection(this.reconnectAttempt > 0 ? 'reconnecting' : 'connecting')
    try {
      this.ws = new WebSocket(this.buildUrl())
    } catch (error) {
      this.emitConnection('closed', error instanceof Error ? error.message : String(error))
      this.scheduleReconnect()
      return
    }

    this.ws.onopen = () => {
      this.reconnectAttempt = 0
      this.emitConnection('connected')
      void this.notify('client.ready', {
        userAgent: navigator.userAgent,
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      })
    }

    this.ws.onmessage = (event) => this.handleMessage(event.data)
    this.ws.onerror = () => this.emitConnection('reconnecting', 'WebSocket 连接异常')
    this.ws.onclose = () => {
      this.rejectAllPending('WebSocket 已关闭')
      if (this.closedByUser) {
        this.emitConnection('closed')
        return
      }
      this.reconnectAttempt += 1
      this.emitConnection('reconnecting')
      this.scheduleReconnect()
    }
  }

  close() {
    this.closedByUser = true
    if (this.reconnectTimer != null) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.ws?.close()
    this.ws = null
    this.rejectAllPending('客户端已关闭')
  }

  request(method: string, params?: unknown): Promise<unknown> {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error('WebSocket 未连接'))
    }
    const id = String(this.nextId)
    this.nextId += 1
    const message: JsonRpcRequest = {
      id,
      method,
      params,
      sessionId: sessionIdFrom(params),
    }
    const promise = new Promise<unknown>((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
    })
    this.ws.send(JSON.stringify(message))
    return promise
  }

  notify(method: string, params?: unknown): Promise<void> {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return Promise.resolve()
    }
    const message: JsonRpcNotification = {
      method,
      params,
      sessionId: sessionIdFrom(params),
    }
    this.ws.send(JSON.stringify(message))
    return Promise.resolve()
  }

  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN
  }

  private handleMessage(data: unknown) {
    if (typeof data !== 'string') return
    let parsed: JsonRpcResponse | JsonRpcNotification
    try {
      parsed = JSON.parse(data) as JsonRpcResponse | JsonRpcNotification
    } catch {
      this.onEvent({ type: 'stream', payload: { text: data } })
      return
    }

    if ('type' in parsed && parsed.type === 'rpc.result') {
      const responseId = parsed.id
      const pending = responseId == null ? null : this.pending.get(responseId)
      if (pending) {
        this.pending.delete(responseId as string)
        pending.resolve(parsed.payload)
      }
      this.onEvent(mapGatewayEvent(parsed))
      return
    }

    if ('type' in parsed && parsed.type === 'rpc.error') {
      const responseId = parsed.id
      const pending = responseId == null ? null : this.pending.get(responseId)
      if (pending) {
        this.pending.delete(responseId as string)
        pending.reject(new Error(errorMessage(parsed.payload)))
      }
      this.onEvent(mapGatewayEvent(parsed))
      return
    }

    if ('type' in parsed && typeof parsed.type === 'string') {
      this.onEvent(mapGatewayEvent(parsed))
      return
    }

    if ('method' in parsed) {
      this.onEvent(mapNotification(parsed))
    }
  }

  private buildUrl(): string {
    const token = getApiKey()
    const base = getBaseUrlValue()
    const wsProtocol = base
      ? base.startsWith('https')
        ? 'wss:'
        : 'ws:'
      : location.protocol === 'https:'
        ? 'wss:'
        : 'ws:'
    const host = base ? new URL(base).host : location.host
    const query = token ? `?token=${encodeURIComponent(token)}` : ''
    return `${wsProtocol}//${host}${this.endpoint}${query}`
  }

  private scheduleReconnect() {
    const delay = Math.min(12000, 1000 + this.reconnectAttempt * 1500)
    this.reconnectTimer = window.setTimeout(() => this.connect(), delay)
  }

  private emitConnection(state: TuiEvent['payload'], error?: string) {
    this.onEvent({
      type: 'connection',
      payload: {
        state,
        reconnectAttempt: this.reconnectAttempt,
        error,
      },
    })
  }

  private rejectAllPending(message: string) {
    for (const pending of this.pending.values()) {
      pending.reject(new Error(message))
    }
    this.pending.clear()
  }
}

function mapNotification(notification: JsonRpcNotification): TuiEvent {
  const method = notification.method
  if (method === 'tui.session') return { type: 'session', payload: notification.params }
  if (method === 'tui.history') return { type: 'history', payload: notification.params }
  if (method === 'tui.stream') return { type: 'stream', payload: notification.params }
  if (method === 'tui.busy') return { type: 'busy', payload: notification.params }
  if (method === 'tui.approval') return { type: 'approval', payload: notification.params }
  if (method === 'tui.model') return { type: 'model', payload: notification.params }
  if (method === 'tui.command') return { type: 'command', payload: notification.params }
  return { type: 'notice', payload: { text: `${method}: ${JSON.stringify(notification.params ?? {})}` } }
}

function mapGatewayEvent(message: JsonRpcResponse): TuiEvent {
  const payload: Record<string, unknown> = {
    ...(objectPayload(message.payload)),
    sessionId: message.sessionId,
    seq: message.seq,
    gatewayType: message.type,
  }
  switch (message.type) {
    case 'gateway.ready':
      return { type: 'command', payload: { commands: commandOptions(payload.commands) } }
    case 'session.created':
    case 'session.resumed':
      return { type: 'session', payload: sessionPayload(payload) }
    case 'session.updated':
      return { type: 'session', payload: sessionPayload(payload) }
    case 'user.message':
      return { type: 'history', payload: { role: 'user', text: payload.content, sessionId: payload.sessionId } }
    case 'assistant.delta':
      return { type: 'stream', payload: { text: payload.delta, sessionId: payload.sessionId } }
    case 'assistant.final':
      return { type: 'history', payload: { role: 'assistant', text: payload.content, sessionId: payload.sessionId, status: 'done' } }
    case 'assistant.reasoning':
      return { type: 'history', payload: { role: 'system', text: `reasoning: ${payload.delta}`, sessionId: payload.sessionId } }
    case 'tool.started':
      return { type: 'history', payload: { role: 'tool', text: `开始工具 ${payload.tool}\n${payload.preview || ''}`, sessionId: payload.sessionId, status: 'pending' } }
    case 'tool.completed':
      return { type: 'history', payload: { role: 'tool', text: `完成工具 ${payload.tool} (${payload.duration_ms || 0}ms)\n${payload.preview || ''}`, sessionId: payload.sessionId } }
    case 'run.snapshot':
    case 'run.event':
    case 'tool.call':
    case 'subagent.updated':
    case 'recovery.updated':
    case 'run.control':
    case 'session.controls':
      return { type: 'notice', payload: { timeline: timelinePayload(message.type, payload) } }
    case 'cron.snapshot':
    case 'kanban.snapshot':
    case 'mcp.snapshot':
    case 'acp.snapshot':
      return { type: 'integration', payload }
    case 'approval.snapshot':
      return { type: 'approval', payload: { approvals: Array.isArray(payload.approvals) ? payload.approvals : [], sessionId: payload.sessionId, replace: true } }
    case 'approval.request':
      return { type: 'approval', payload: { ...payload, status: 'pending' } }
    case 'approval.response':
      return { type: 'approval', payload: { ...payload, status: payload.status || (payload.choice === 'deny' ? 'denied' : 'approved') } }
    case 'run.busy':
    case 'run.queued':
    case 'run.interrupted':
    case 'run.idle':
    case 'run.completed':
      return { type: 'busy', payload: busyPayload(payload) }
    case 'run.failed':
      return { type: 'history', payload: { role: 'system', text: `运行失败：${payload.error || '未知错误'}`, sessionId: payload.sessionId, status: 'error' } }
    case 'model.changed':
      return { type: 'model', payload: modelPayload(payload) }
    case 'rpc.result':
      return mapRpcResult(payload)
    case 'rpc.error':
      return { type: 'notice', payload: { text: errorMessage(message.payload) } }
    default:
      return { type: 'notice', payload: { text: `${message.type}: ${JSON.stringify(message.payload ?? {})}` } }
  }
}

function mapRpcResult(payload: Record<string, unknown>): TuiEvent {
  if (Array.isArray(payload.sessions)) {
    return { type: 'session', payload: sessionPayload(payload) }
  }
  if (Array.isArray(payload.providers)) {
    return { type: 'model', payload: modelPayload(payload) }
  }
  if (Array.isArray(payload.approvals)) {
    return { type: 'approval', payload: { approvals: payload.approvals, replace: true } }
  }
  if (Array.isArray(payload.commands)) {
    return { type: 'command', payload: { commands: commandOptions(payload.commands) } }
  }
  if (payload.integrations || payload.cron || payload.kanban || payload.mcp || payload.acp) {
    return { type: 'integration', payload: payload.integrations || payload }
  }
  return { type: 'notice', payload: { text: '' } }
}

function sessionPayload(payload: Record<string, unknown>): Record<string, unknown> {
  const sessions = Array.isArray(payload.sessions)
    ? payload.sessions
    : payload.session_id || payload.id
      ? [payload]
      : undefined
  return {
    activeSessionId: stringValue(payload.session_id || payload.id || payload.sessionId),
    sessions: sessions?.map((item) => normalizeSession(item)),
  }
}

function modelPayload(payload: Record<string, unknown>): Record<string, unknown> {
  const providers = Array.isArray(payload.providers) ? payload.providers : [payload]
  const models = providers.map((item) => {
    const data = objectPayload(item)
    const defaultModel = stringValue(data.default_model || data.model)
    const provider = stringValue(data.provider || data.providerKey)
    const id = defaultModel && provider ? `${provider}:${defaultModel}` : defaultModel || provider || 'default'
    return {
      id,
      label: stringValue(data.label) || id,
      provider,
      context: stringValue(data.dialect || data.context),
    }
  })
  return {
    activeModelId: stringValue(payload.model) || undefined,
    models,
  }
}

function busyPayload(payload: Record<string, unknown>): Record<string, unknown> {
  return {
    busy: Boolean(payload.running),
    policy: stringValue(payload.busy_mode) || 'queue',
    queuedInputs: payload.queued_count ? new Array(Number(payload.queued_count)).fill('queued') : [],
    activeRun: payload.active_run,
    runId: payload.run_id || payload.agent_run_id,
  }
}

function timelinePayload(type: string, payload: Record<string, unknown>): Record<string, unknown> {
  const title = timelineTitle(type, payload)
  return {
    id: stringValue(payload.event_id || payload.tool_call_id || payload.subagent_id || payload.recovery_id || payload.command_id || payload.run_id || payload.event_seq) || `${type}-${Date.now()}`,
    kind: timelineKind(type),
    title,
    detail: timelineDetail(type, payload),
    status: stringValue(payload.status || payload.phase || payload.event_type),
    severity: stringValue(payload.severity) || (stringValue(payload.error) ? 'error' : 'info'),
    runId: stringValue(payload.run_id),
    sessionId: stringValue(payload.session_id || payload.sessionId),
    createdAt: numberValue(payload.created_at || payload.started_at || payload.last_activity_at || payload.event_seq, Date.now()),
    seq: numberValue(payload.event_seq || payload.seq, 0),
  }
}

function timelineKind(type: string): string {
  if (type === 'run.snapshot') return 'run'
  if (type === 'run.event') return 'event'
  if (type === 'tool.call') return 'tool'
  if (type === 'subagent.updated') return 'subagent'
  if (type === 'recovery.updated') return 'recovery'
  if (type === 'run.control') return 'control'
  return 'event'
}

function timelineTitle(type: string, payload: Record<string, unknown>): string {
  if (type === 'run.snapshot') return `运行 ${stringValue(payload.status || payload.phase) || 'snapshot'}`
  if (type === 'run.event') return stringValue(payload.event_type) || '运行事件'
  if (type === 'tool.call') return `工具 ${stringValue(payload.tool_name) || ''}`.trim()
  if (type === 'subagent.updated') return `子 Agent ${stringValue(payload.name) || stringValue(payload.status)}`
  if (type === 'recovery.updated') return `恢复 ${stringValue(payload.recovery_type) || stringValue(payload.status)}`
  if (type === 'run.control') return `控制 ${stringValue(payload.command) || stringValue(payload.status)}`
  return type
}

function timelineDetail(type: string, payload: Record<string, unknown>): string {
  if (type === 'run.snapshot') {
    return [
      stringValue(payload.input_preview),
      stringValue(payload.recovery_hint),
      stringValue(payload.error),
    ].filter(Boolean).join('\n')
  }
  if (type === 'run.event') return stringValue(payload.summary)
  if (type === 'tool.call') return [stringValue(payload.args_preview), stringValue(payload.result_preview), stringValue(payload.error)].filter(Boolean).join('\n')
  if (type === 'subagent.updated') return [stringValue(payload.goal_preview), stringValue(payload.error)].filter(Boolean).join('\n')
  if (type === 'recovery.updated') return stringValue(payload.summary)
  if (type === 'run.control') return JSON.stringify(payload.payload || {})
  return JSON.stringify(payload)
}

function commandOptions(value: unknown): { name: string; description: string }[] {
  if (!Array.isArray(value)) return []
  return value
    .filter((item): item is string => typeof item === 'string')
    .map((name) => ({ name, description: slashDescription(name) }))
}

function normalizeSession(item: unknown): Record<string, unknown> {
  const data = objectPayload(item)
  return {
    id: stringValue(data.id || data.session_id),
    title: stringValue(data.title) || stringValue(data.session_id || data.id) || '未命名会话',
    cwd: stringValue(data.cwd) || 'workspace',
    model: stringValue(data.model) || 'default',
    branch: stringValue(data.branch || data.branch_name),
    createdAt: numberValue(data.createdAt || data.started_at || data.last_active, Date.now()),
    active: Boolean(data.active),
  }
}

function slashDescription(command: string): string {
  if (command.startsWith('/model')) return '模型选择与切换'
  if (command.startsWith('/busy')) return '运行中输入策略'
  if (command.startsWith('/approve')) return '审批待确认操作'
  if (command.startsWith('/cron')) return '定时任务控制'
  if (command.startsWith('/kanban')) return '看板任务控制'
  if (command.startsWith('/mcp') || command.startsWith('/reload-mcp')) return 'MCP 状态与重载'
  if (command.startsWith('/session') || command.startsWith('/resume')) return '会话浏览与恢复'
  return 'Slash 命令'
}

function sessionIdFrom(params: unknown): string | undefined {
  const payload = objectPayload(params)
  const value = payload.sessionId || payload.session_id
  return typeof value === 'string' && value ? value : undefined
}

function errorMessage(payload: unknown): string {
  const data = objectPayload(payload)
  return stringValue(data.message) || '请求失败'
}

function objectPayload(payload: unknown): Record<string, unknown> {
  return payload && typeof payload === 'object' ? payload as Record<string, unknown> : {}
}

function stringValue(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function numberValue(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}
