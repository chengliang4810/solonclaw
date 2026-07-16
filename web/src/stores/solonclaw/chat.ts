import { cancelRun, startRun, streamRunEvents, uploadChatFiles, type ChatMessage, type RunEvent } from '@/api/solonclaw/chat'
import { getBaseUrlValue, getManagementProfile } from '@/api/client'
import { getAuthScopeId, onAuthContextChange } from '@/api/sessionAuth'
import { fetchRunDetail } from '@/api/solonclaw/runs'
import { deleteSession as deleteSessionApi, fetchLatestSessionDescendant, fetchSession, fetchSessions, fetchSessionUsageSingle, type SolonClawMessage, type SessionGoalState, type SessionSummary } from '@/api/solonclaw/sessions'
import { shouldUseServerMessages } from '@/shared/chatMessageMerge'
import { chatCacheKey, recoverChatCacheQuota } from '@/shared/chatCacheScope'
import { profileSessionIdentity } from '@/shared/profileScope'
import { normalizeTimestampMs } from '@/shared/session-display'
import { defineStore } from 'pinia'
import { computed, onScopeDispose, ref } from 'vue'
import { useAppStore } from './app'
import { useProfilesStore } from './profiles'

export interface Attachment {
  id: string
  name: string
  type: string
  size: number
  url: string
  file?: File
}

export interface Message {
  id: string
  role: 'user' | 'assistant' | 'system' | 'tool'
  content: string
  timestamp: number
  toolName?: string
  toolPreview?: string
  toolArgs?: string
  toolResult?: string
  toolStatus?: 'running' | 'done' | 'error'
  reasoning?: string
  isStreaming?: boolean
  attachments?: Attachment[]
}

export interface Session {
  /** 浏览器内部复合标识；服务端原始会话 ID 仍保存在 id。 */
  key: string
  id: string
  profile?: string
  title: string
  source?: string
  messages: Message[]
  createdAt: number
  updatedAt: number
  model?: string
  provider?: string
  messageCount?: number
  inputTokens?: number
  outputTokens?: number
  lastTotalTokens?: number
  endedAt?: number | null
  lastActiveAt?: number
  isLive?: boolean
  goalState?: SessionGoalState | null
}

let chatUidCounter = 0

function uid(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID()
  }
  chatUidCounter += 1
  return `${Date.now().toString(36)}-${chatUidCounter.toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

/** 拆分旧缓存或异常接口中混入正文的思考标签，避免思考内容重复展示。 */
function splitLegacyThinkContent(content?: string | null): { content: string; reasoning?: string } {
  const value = content || ''
  const match = value.match(/^\s*<think>([\s\S]*?)<\/think>\s*/i)
  if (!match) return { content: value.replace(/^\s*<\/think>\s*/i, '') }
  return {
    content: value.slice(match[0].length),
    reasoning: match[1].trim() || undefined,
  }
}

function mapSolonClawMessages(msgs: SolonClawMessage[]): Message[] {
  // Build lookups from assistant messages with tool_calls
  const toolNameMap = new Map<string, string>()
  const toolArgsMap = new Map<string, string>()
  for (const msg of msgs) {
    if (msg.role === 'assistant' && msg.tool_calls) {
      for (const tc of msg.tool_calls) {
        if (tc.id) {
          if (tc.function?.name) toolNameMap.set(tc.id, tc.function.name)
          if (tc.function?.arguments) toolArgsMap.set(tc.id, tc.function.arguments)
        }
      }
    }
  }

  const result: Message[] = []
  for (const msg of msgs) {
    // Skip assistant messages that only contain tool_calls (no meaningful content)
    if (msg.role === 'assistant' && msg.tool_calls?.length && !msg.content?.trim()) {
      // Emit a tool.started message for each tool call
      for (const tc of msg.tool_calls) {
        result.push({
          id: String(msg.id) + '_' + tc.id,
          role: 'tool',
          content: '',
          timestamp: Math.round(msg.timestamp * 1000),
          toolName: tc.function?.name || 'tool',
          toolArgs: tc.function?.arguments || undefined,
          toolStatus: 'done',
        })
      }
      continue
    }

    // Tool result messages
    if (msg.role === 'tool') {
      const tcId = msg.tool_call_id || ''
      const toolName = msg.tool_name || toolNameMap.get(tcId) || 'tool'
      const toolArgs = toolArgsMap.get(tcId) || undefined
      // Extract a short preview from the content
      let preview = ''
      let replayStatus: 'done' | 'error' = msg.tool_status === 'error' ? 'error' : 'done'
      if (msg.content) {
        try {
          const parsed = JSON.parse(msg.content)
          preview = parsed.url || parsed.title || parsed.preview || parsed.summary || ''
          // 旧会话没有持久化状态字段时，兼容项目统一工具结果 envelope。
          if (!msg.tool_status && parsed.status === 'error') {
            replayStatus = 'error'
            preview = parsed.error || parsed.summary || preview
          }
        } catch {
          preview = msg.content.slice(0, 80)
        }
      }
      // Find and remove the matching placeholder from tool_calls above
      const placeholderIdx = result.findIndex(
        m => m.role === 'tool' && m.toolName === toolName && !m.toolResult && m.id.includes('_' + tcId)
      )
      if (placeholderIdx !== -1) {
        result.splice(placeholderIdx, 1)
      }
      result.push({
        id: String(msg.id),
        role: 'tool',
        content: '',
        timestamp: Math.round(msg.timestamp * 1000),
        toolName,
        toolArgs,
        toolPreview: typeof preview === 'string' ? preview.slice(0, 100) || undefined : undefined,
        toolResult: msg.content || undefined,
        toolStatus: replayStatus,
      })
      continue
    }

    // Normal user/assistant messages
    const normalized = msg.role === 'assistant'
      ? splitLegacyThinkContent(msg.content)
      : { content: msg.content || '', reasoning: undefined }
    result.push({
      id: String(msg.id),
      role: msg.role,
      content: normalized.content,
      reasoning: msg.reasoning || normalized.reasoning,
      timestamp: Math.round(msg.timestamp * 1000),
    })
  }
  return result
}

function mapSolonClawSession(s: SessionSummary, fallbackProfile: string): Session {
  const profile = s.profile || fallbackProfile || 'default'
  return {
    key: profileSessionIdentity(s.id, profile),
    id: s.id,
    profile,
    title: s.title || '',
    source: s.source || undefined,
    messages: [],
    createdAt: normalizeTimestampMs(s.started_at),
    updatedAt: normalizeTimestampMs(s.last_active || s.ended_at || s.started_at),
    model: s.model,
    provider: s.provider || '',
    messageCount: s.message_count,
    inputTokens: s.input_tokens,
    outputTokens: s.output_tokens,
    lastTotalTokens: s.last_total_tokens || undefined,
    endedAt: s.ended_at != null ? normalizeTimestampMs(s.ended_at) : null,
    lastActiveAt: s.last_active != null ? normalizeTimestampMs(s.last_active) : undefined,
    isLive: Boolean(s.is_active),
    goalState: s.goal_state || null,
  }
}

// Cache keys for stale-while-revalidate loading of sessions / messages.
// Rendering from cache on boot avoids the multi-round-trip wait the user sees
// every time they open the page (esp. noticeable on mobile).
const IN_FLIGHT_TTL_MS = 15 * 60 * 1000 // Give up after 15 minutes
const POLL_INTERVAL_MS = 2000
const POLL_STABLE_EXITS = 3 // 3 × 2s = 6s of no change → assume run finished

/** 构造当前认证主体和服务端专属的缓存键。 */
function scopedCacheKey(category: string, suffix = ''): string | null {
  return chatCacheKey(getBaseUrlValue(), getAuthScopeId(), category, suffix)
}
/** 返回活动会话缓存键。 */
function storageKey(): string | null { return scopedCacheKey('active') }
/** 读取当前认证作用域中的活动会话。 */
function readActiveSessionKey(): string | null {
  const key = storageKey()
  return key ? localStorage.getItem(key) : null
}
/** 返回会话列表缓存键。 */
function sessionsCacheKey(): string | null { return scopedCacheKey('sessions') }
/** 返回指定会话的消息缓存键。 */
function msgsCacheKey(sessionKey: string): string | null { return scopedCacheKey('messages', sessionKey) }
/** 返回指定会话的运行恢复缓存键。 */
function inFlightKey(sessionKey: string): string | null { return scopedCacheKey('in-flight', sessionKey) }

interface InFlightRun {
  runId: string
  agentRunId?: string
  startedAt: number
}

function loadJson<T>(key: string | null): T | null {
  if (!key) return null
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : null
  } catch {
    return null
  }
}

/** 清洗旧浏览器缓存中的思考标签，避免脏缓存阻止服务端干净消息覆盖。 */
function normalizeCachedMessages(messages: Message[]): Message[] {
  return messages.map(message => {
    if (message.role !== 'assistant') return message
    const normalized = splitLegacyThinkContent(message.content)
    return {
      ...message,
      content: normalized.content,
      reasoning: message.reasoning || normalized.reasoning,
    }
  })
}

function isQuotaExceededError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false
  const e = error as { name?: string, code?: number }
  return e.name === 'QuotaExceededError' || e.code === 22 || e.code === 1014
}

function recoverStorageQuota() {
  recoverChatCacheQuota()
}

function setItemBestEffort(key: string | null, value: string) {
  if (!key) return
  try {
    localStorage.setItem(key, value)
    return
  } catch (error) {
    if (!isQuotaExceededError(error)) return
  }

  recoverStorageQuota()

  try {
    localStorage.setItem(key, value)
  } catch {
    // quota exceeded or private mode — ignore, cache is best-effort
  }
}

function saveJson(key: string | null, value: unknown) {
  if (!key) return
  try {
    setItemBestEffort(key, JSON.stringify(value))
  } catch {
    // quota exceeded or private mode — ignore, cache is best-effort
  }
}

function removeItem(key: string | null) {
  if (!key) return
  try {
    localStorage.removeItem(key)
  } catch {
    // ignore
  }
}

// Strip the circular `file: File` reference from attachments before caching —
// File objects don't serialize and we only need name/type/size/url for display.
function sanitizeForCache(msgs: Message[]): Message[] {
  return msgs.map(m => {
    if (!m.attachments?.length) return m
    return {
      ...m,
      attachments: m.attachments.map(a => ({ id: a.id, name: a.name, type: a.type, size: a.size, url: a.url })),
    }
  })
}

function isTerminalRunStatus(status?: string): boolean {
  return status === 'success' || status === 'failed' || status === 'cancelled'
}

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<Session[]>([])
  const activeSessionKey = ref<string | null>(null)
  const focusMessageId = ref<string | null>(null)
  const streamStates = ref<Map<string, AbortController>>(new Map())
  const runIds = ref<Map<string, string>>(new Map())
  const isStreaming = computed(() => activeSessionKey.value != null && streamStates.value.has(activeSessionKey.value))
  const isLoadingSessions = ref(false)
  const sessionsLoaded = ref(false)
  const isLoadingMessages = ref(false)
  // tmux-like resume state: true when we recovered an in-flight run from
  // localStorage after a refresh and are polling fetchSession for progress.
  // UI shows the thinking indicator while this is set.
  const resumingRuns = ref<Set<string>>(new Set())
  const isRunActive = computed(() =>
    isStreaming.value
    || (activeSessionKey.value != null && resumingRuns.value.has(activeSessionKey.value))
  )
  const pollTimers = new Map<string, ReturnType<typeof setInterval>>()
  const pollSignatures = new Map<string, { sig: string, stableTicks: number }>()
  let authContextVersion = 0

  const activeSession = ref<Session | null>(null)
  const activeSessionId = computed(() => activeSession.value?.id || null)
  const messages = computed<Message[]>(() => activeSession.value?.messages || [])

  /** 认证主体或服务端变化时停止旧调用，并清空全部聊天内存态。 */
  function resetForAuthContextChange(): void {
    authContextVersion += 1
    for (const controller of streamStates.value.values()) controller.abort()
    for (const timer of pollTimers.values()) clearInterval(timer)
    pollTimers.clear()
    pollSignatures.clear()
    streamStates.value = new Map()
    runIds.value = new Map()
    resumingRuns.value = new Set()
    sessions.value = []
    activeSessionKey.value = null
    activeSession.value = null
    focusMessageId.value = null
    isLoadingSessions.value = false
    sessionsLoaded.value = false
    isLoadingMessages.value = false
  }

  const stopAuthContextListener = onAuthContextChange(resetForAuthContextChange)
  onScopeDispose(() => {
    stopAuthContextListener()
    resetForAuthContextChange()
  })

  /** 判断异步操作是否仍属于发起时的认证主体和服务端。 */
  function isCurrentAuthContext(version: number): boolean {
    return version === authContextVersion
  }

  function isSessionLive(sessionKey: string): boolean {
    if (streamStates.value.has(sessionKey) || resumingRuns.value.has(sessionKey)) return true

    const session = sessions.value.find(candidate => candidate.key === sessionKey)
    return Boolean(session?.isLive)
  }

  function persistSessionsList() {
    // Cache lightweight summaries only (messages are cached per-session).
    saveJson(
      sessionsCacheKey(),
      sessions.value.map(s => ({ ...s, messages: [] })),
    )
  }

  function persistSessionMessages(sessionKey: string) {
    const session = sessions.value.find(candidate => candidate.key === sessionKey)
    if (session) saveJson(msgsCacheKey(sessionKey), sanitizeForCache(session.messages))
  }

  function markInFlight(sid: string, runId: string, agentRunId?: string) {
    saveJson(inFlightKey(sid), { runId, agentRunId, startedAt: Date.now() } as InFlightRun)
  }

  function markAgentRunInFlight(sid: string, agentRunId?: string) {
    if (!agentRunId) return
    const current = readInFlight(sid)
    if (!current) return
    saveJson(inFlightKey(sid), { ...current, agentRunId } as InFlightRun)
  }

  function clearInFlight(sid: string) {
    removeItem(inFlightKey(sid))
  }

  function readInFlight(sid: string): InFlightRun | null {
    const rec = loadJson<InFlightRun>(inFlightKey(sid))
    if (!rec) return null
    if (Date.now() - rec.startedAt > IN_FLIGHT_TTL_MS) {
      removeItem(inFlightKey(sid))
      return null
    }
    return rec
  }

  function sessionForKey(sessionKey: string): Session | undefined {
    return sessions.value.find(session => session.key === sessionKey)
  }

  function profileForSession(sessionKey: string): string | undefined {
    return sessionForKey(sessionKey)?.profile
  }

  function resolveSessionKey(sessionIdOrKey: string, profile?: string): string | null {
    if (sessionForKey(sessionIdOrKey)) return sessionIdOrKey
    if (profile?.trim()) {
      const explicit = profileSessionIdentity(sessionIdOrKey, profile)
      if (sessionForKey(explicit)) return explicit
    }
    if (activeSession.value?.id === sessionIdOrKey) return activeSession.value.key
    return sessions.value.find(session => session.id === sessionIdOrKey)?.key || null
  }

  async function resolveLatestDescendantKey(sessionKey: string): Promise<string> {
    const session = sessionForKey(sessionKey)
    if (!session) return sessionKey
    const latest = await fetchLatestSessionDescendant(session.id, session.profile)
    if (!latest?.session_id) return sessionKey
    const latestKey = profileSessionIdentity(latest.session_id, session.profile)
    return sessionForKey(latestKey) ? latestKey : sessionKey
  }

  function stopPolling(sid: string) {
    const t = pollTimers.get(sid)
    if (t) {
      clearInterval(t)
      pollTimers.delete(sid)
    }
    pollSignatures.delete(sid)
    resumingRuns.value = new Set([...resumingRuns.value].filter(x => x !== sid))
  }

  // Poll fetchSession while an in-flight run is recovering. Exits when the
  // server's message signature is stable for POLL_STABLE_EXITS ticks (run
  // presumed done), TTL elapses, or the user explicitly starts streaming.
  function startPolling(sid: string) {
    if (pollTimers.has(sid)) return
    const startingAuthContextVersion = authContextVersion
    resumingRuns.value = new Set([...resumingRuns.value, sid])
    const timer = setInterval(async () => {
      if (!isCurrentAuthContext(startingAuthContextVersion)) return
      // If a fresh SSE stream started for this session, polling is redundant.
      if (streamStates.value.has(sid)) {
        stopPolling(sid)
        return
      }
      const inFlight = readInFlight(sid)
      if (!inFlight) {
        stopPolling(sid)
        return
      }
      try {
        if (inFlight.agentRunId) {
          const runDetail = await fetchRunDetail(inFlight.agentRunId)
          if (!isCurrentAuthContext(startingAuthContextVersion)) return
          if (!isTerminalRunStatus(runDetail.run?.status)) {
            return
          }
        }
        const session = sessionForKey(sid)
        if (!session) return
        const detail = await fetchSession(session.id, session.profile)
        if (!isCurrentAuthContext(startingAuthContextVersion)) return
        if (!detail) return
        const mapped = mapSolonClawMessages(detail.messages || [])
        const target = sessionForKey(sid)
        if (!target) return
        if (inFlight.agentRunId) {
          target.messages = mapped
          if (detail.title) target.title = detail.title
          target.goalState = detail.goal_state || null
          persistSessionMessages(sid)
          clearInFlight(sid)
          stopPolling(sid)
          return
        }
        // Use the same "content-aware" comparison as switchSession: server
        // is ahead iff it knows about at least as many user turns and its
        // last assistant text is at least as long as ours.
        const local = target.messages
        const localUsers = local.filter(m => m.role === 'user').length
        const serverUsers = mapped.filter(m => m.role === 'user').length
        const serverIsCaughtUp = serverUsers >= localUsers
        // Same rationale as switchSession: strictly more user turns means
        // server is ahead (new turn complete). Equal user turns + longer
        // assistant means server caught up on the current turn.
        if (shouldUseServerMessages(local, mapped)) {
          target.messages = mapped
          if (detail.title && !target.title) target.title = detail.title
          target.goalState = detail.goal_state || null
          persistSessionMessages(sid)
        }
        // Stability detection ONLY matters when the server has at least as
        // many user turns as we do. Otherwise the server is still catching
        // up (e.g. the new turn we just sent hasn't been flushed server-side
        // yet) and a "stable" signature is a false positive — the stability
        // is the server NOT having our latest turn, not the run being done.
        if (!serverIsCaughtUp) {
          pollSignatures.delete(sid)
        } else {
          const last = mapped[mapped.length - 1]
          const sig = `${mapped.length}|${last?.content?.slice(-40) || ''}|${last?.toolStatus || ''}`
          const prev = pollSignatures.get(sid)
          if (prev && prev.sig === sig) {
            prev.stableTicks += 1
            if (prev.stableTicks >= POLL_STABLE_EXITS) {
              // Run is done on the server. Force-apply server view even if
              // our "don't retreat" guard above skipped it — the server is
              // now the authoritative source of truth.
              target.messages = mapped
              if (detail.title) target.title = detail.title
              target.goalState = detail.goal_state || null
              persistSessionMessages(sid)
              clearInFlight(sid)
              stopPolling(sid)
            }
          } else {
            pollSignatures.set(sid, { sig, stableTicks: 0 })
          }
        }
      } catch {
        // transient network error — ignore, next tick tries again
      }
    }, POLL_INTERVAL_MS)
    pollTimers.set(sid, timer)
  }

  async function loadSessions(preferredSessionId?: string | null, preferredProfile?: string) {
    const startingAuthContextVersion = authContextVersion
    isLoadingSessions.value = true
    try {
      // 从会话维度缓存中恢复，实现 instant render
      const cachedSessions = loadJson<Session[]>(sessionsCacheKey())
      if (sessions.value.length === 0 && cachedSessions?.length) {
        sessions.value = cachedSessions
        const savedKey = readActiveSessionKey()
        if (savedKey) {
          const cachedActive = cachedSessions.find(s => s.key === savedKey) || null
          if (cachedActive) {
            const cachedMsgs = loadJson<Message[]>(msgsCacheKey(savedKey))
            if (cachedMsgs) cachedActive.messages = normalizeCachedMessages(cachedMsgs)
            activeSession.value = cachedActive
            activeSessionKey.value = savedKey
          }
        }
      }

      const list = await fetchSessions()
      if (!isCurrentAuthContext(startingAuthContextVersion)) return
      const fallbackProfile = useProfilesStore().managedProfileName || 'default'
      const fresh = list.map(session => mapSolonClawSession(session, fallbackProfile))
      const freshKeys = new Set(fresh.map(session => session.key))
      // Preserve already-loaded messages for sessions that are still present,
      // so we don't blow away the active session's messages on refresh.
      const msgsByKeyBefore = new Map(sessions.value.map(session => [session.key, session.messages]))
      for (const session of fresh) {
        const previous = msgsByKeyBefore.get(session.key)
        if (previous?.length) session.messages = previous
      }
      // 只保留仍可恢复的本地会话；旧浏览器缓存不能覆盖服务端空列表。
      const localOnly = sessions.value.filter(session =>
        !freshKeys.has(session.key)
        && (readInFlight(session.key) || streamStates.value.has(session.key) || resumingRuns.value.has(session.key))
      )
      sessions.value = [...localOnly, ...fresh]
      persistSessionsList()

      const preferredKey = preferredSessionId
        ? resolveSessionKey(preferredSessionId, preferredProfile)
        : null
      const savedKey = readActiveSessionKey()
      const candidateKey = preferredKey
        || (savedKey && sessionForKey(savedKey) ? savedKey : null)
        || (activeSessionKey.value && sessionForKey(activeSessionKey.value) ? activeSessionKey.value : null)
        || sessions.value[0]?.key
      const targetKey = candidateKey ? await resolveLatestDescendantKey(candidateKey) : candidateKey
      if (!isCurrentAuthContext(startingAuthContextVersion)) return
      if (targetKey) {
        if (targetKey === activeSessionKey.value && activeSession.value && streamStates.value.has(targetKey)) {
          setItemBestEffort(storageKey(), targetKey)
        } else {
          await switchSession(targetKey)
        }
      } else {
        activeSessionKey.value = null
        activeSession.value = null
        focusMessageId.value = null
        removeItem(storageKey())
      }
    } catch (err) {
      if (isCurrentAuthContext(startingAuthContextVersion)) {
        console.error('Failed to load sessions:', err)
      }
    } finally {
      if (isCurrentAuthContext(startingAuthContextVersion)) {
        isLoadingSessions.value = false
        sessionsLoaded.value = true
      }
    }
  }

  // Re-pull active session from server and overwrite local messages. Used on
  // SSE drop and on tab-visible events — mobile browsers kill EventSource
  // while backgrounded, but the backend run usually completes anyway.
  async function refreshActiveSession(): Promise<boolean> {
    const startingAuthContextVersion = authContextVersion
    const sessionKey = activeSessionKey.value
    const target = sessionKey ? sessionForKey(sessionKey) : undefined
    if (!sessionKey || !target) return false
    try {
      const detail = await fetchSession(target.id, target.profile)
      if (!isCurrentAuthContext(startingAuthContextVersion)) return false
      if (!detail) return false
      const mapped = mapSolonClawMessages(detail.messages || [])
      // SSE 断开后的主动刷新可能早于后端会话落库完成，不能用旧服务端视图
      // 覆盖本地仍在推进的新用户轮次；统一复用会话切换的内容感知合并规则。
      if (shouldUseServerMessages(target.messages, mapped)) {
        target.messages = mapped
      }
      target.goalState = detail.goal_state || null
      target.inputTokens = detail.input_tokens
      target.outputTokens = detail.output_tokens
      target.lastTotalTokens = detail.last_total_tokens || undefined
      if (detail.title) target.title = detail.title
      persistSessionMessages(sessionKey)
      return true
    } catch (err) {
      if (isCurrentAuthContext(startingAuthContextVersion)) {
        console.error('Failed to refresh active session:', err)
      }
      return false
    }
  }


  function createSession(): Session {
    const profile = useProfilesStore().managedProfileName || getManagementProfile() || 'default'
    const id = uid()
    const session: Session = {
      key: profileSessionIdentity(id, profile),
      id,
      profile,
      title: '',
      source: 'api_server',
      messages: [],
      createdAt: Date.now(),
      updatedAt: Date.now(),
    }
    sessions.value.unshift(session)
    // Persist immediately so a refresh before run.completed can still find
    // this session in the cache.
    persistSessionsList()
    return session
  }

  async function switchSession(
    sessionIdOrKey: string,
    focusId?: string | null,
    profile?: string,
  ) {
    const startingAuthContextVersion = authContextVersion
    const sessionKey = resolveSessionKey(sessionIdOrKey, profile)
    if (!sessionKey) return
    const target = sessionForKey(sessionKey)
    if (!target) return

    activeSessionKey.value = sessionKey
    focusMessageId.value = focusId ?? null
    setItemBestEffort(storageKey(), sessionKey)
    activeSession.value = target

    // Hydrate messages from localStorage cache first (instant render), then
    // revalidate from server in the background. If no cache exists, show the
    // loading state while we fetch.
    const hasLocalMessages = target.messages.length > 0
    if (!hasLocalMessages) {
      const cachedMsgs = loadJson<Message[]>(msgsCacheKey(sessionKey))
      if (cachedMsgs?.length) {
        target.messages = normalizeCachedMessages(cachedMsgs)
      }
    }

    const needsBlockingLoad = target.messages.length === 0
    if (needsBlockingLoad) isLoadingMessages.value = true

    try {
      const detail = await fetchSession(target.id, target.profile)
      if (!isCurrentAuthContext(startingAuthContextVersion)) return
      if (detail && detail.messages) {
        const mapped = mapSolonClawMessages(detail.messages)
        // Pick whichever view has more information. Simple length comparison
        // is wrong because mapSolonClawMessages folds tool_call-only assistant
        // msgs and matches them with tool-result msgs — so post-fold `mapped`
        // can be SHORTER than the raw SSE-built local array even when the
        // server is strictly ahead. Instead, compare the last assistant
        // message content: if the server's is at least as long, the server
        // is up-to-date (and has the final complete text); otherwise keep
        // local (in-flight window where server hasn't flushed the new turn).
        const local = target.messages
        // Trust server when:
        //   - it has STRICTLY MORE user turns than we do (new turn landed),
        //     OR
        //   - same user-turn count AND server's last assistant is at least
        //     as long as ours (same turn, server caught up or further)
        // Otherwise keep local (protects against the server-not-yet-flushed
        // race during in-flight runs). Length comparison alone is wrong
        // across different turns because each turn's last assistant is
        // unrelated to the previous turn's.
        if (shouldUseServerMessages(local, mapped)) {
          target.messages = mapped
        }
        target.goalState = detail.goal_state || null
        target.inputTokens = detail.input_tokens
        target.outputTokens = detail.output_tokens
        target.lastTotalTokens = detail.last_total_tokens || undefined
        // Update title: use Jimuqu title, or fallback to first user message
        if (detail.title) {
          target.title = detail.title
        } else if (!target.title) {
          const firstUser = target.messages.find(m => m.role === 'user')
          if (firstUser) {
            const t = firstUser.content.slice(0, 40)
            target.title = t + (firstUser.content.length > 40 ? '...' : '')
          }
        }
        persistSessionMessages(sessionKey)
      }
    } catch (err) {
      if (isCurrentAuthContext(startingAuthContextVersion)) {
        console.error('Failed to load session messages:', err)
      }
    } finally {
      if (isCurrentAuthContext(startingAuthContextVersion)) {
        isLoadingMessages.value = false
      }
    }

    if (!isCurrentAuthContext(startingAuthContextVersion)) return

    // tmux-like resume: if this session has a recent in-flight run and we're
    // not currently streaming, start polling fetchSession to pick up progress
    // that happened while we were gone. Exits automatically on stability.
    if (readInFlight(sessionKey) && !streamStates.value.has(sessionKey)) {
      startPolling(sessionKey)
    }

    // Fetch token usage for this session from web-ui DB
    try {
      const usage = await fetchSessionUsageSingle(target.id, target.profile)
      if (!isCurrentAuthContext(startingAuthContextVersion)) return
      if (usage) {
        target.inputTokens = usage.input_tokens
        target.outputTokens = usage.output_tokens
        target.lastTotalTokens = usage.last_total_tokens || undefined
      }
    } catch { /* non-critical */ }
  }

  function newChat() {
    if (isStreaming.value) return
    const session = createSession()
    // Inherit current global model
    const appStore = useAppStore()
    session.model = appStore.selectedModel || undefined
    void switchSession(session.key)
  }

  async function switchSessionModel(modelId: string, provider?: string) {
    if (!activeSession.value) return
    activeSession.value.model = modelId
    activeSession.value.provider = provider || ''
    // If provider changed, update global config too.
    if (provider) {
      await useAppStore().switchModel(modelId, provider)
    }
  }

  async function deleteSession(sessionKey: string): Promise<boolean> {
    const startingAuthContextVersion = authContextVersion
    const target = sessionForKey(sessionKey)
    if (!target) return false
    const ok = await deleteSessionApi(target.id, target.profile)
    if (!isCurrentAuthContext(startingAuthContextVersion)) return false
    if (!ok) return false
    sessions.value = sessions.value.filter(session => session.key !== sessionKey)
    removeItem(msgsCacheKey(sessionKey))
    persistSessionsList()
    if (activeSessionKey.value === sessionKey) {
      if (sessions.value.length > 0) {
        await switchSession(sessions.value[0].key)
      } else {
        const session = createSession()
        await switchSession(session.key)
      }
    }
    return true
  }

  function getSessionMsgs(sessionKey: string): Message[] {
    return sessionForKey(sessionKey)?.messages || []
  }

  function addMessage(sessionKey: string, msg: Message) {
    const session = sessionForKey(sessionKey)
    if (session) session.messages.push(msg)
  }

  function updateMessage(sessionKey: string, id: string, update: Partial<Message>) {
    const session = sessionForKey(sessionKey)
    if (!session) return
    const idx = session.messages.findIndex(m => m.id === id)
    if (idx !== -1) {
      session.messages[idx] = { ...session.messages[idx], ...update }
    }
  }

  function updateSessionTitle(sessionKey: string) {
    const target = sessionForKey(sessionKey)
    if (!target) return
    if (!target.title) {
      const firstUser = target.messages.find(m => m.role === 'user')
      if (firstUser) {
        const title = firstUser.attachments?.length
          ? firstUser.attachments.map(a => a.name).join(', ')
          : firstUser.content
        target.title = title.slice(0, 40) + (title.length > 40 ? '...' : '')
      }
    }
    target.updatedAt = Date.now()
  }

  function adoptServerSessionId(localSessionKey: string, serverSessionId: string): string {
    const local = sessionForKey(localSessionKey)
    if (!local || !serverSessionId || serverSessionId === local.id) return localSessionKey

    const serverSessionKey = profileSessionIdentity(serverSessionId, local.profile)
    const existing = sessionForKey(serverSessionKey)
    if (existing && existing !== local) {
      if (existing.messages.length === 0 && local.messages.length > 0) {
        existing.messages = local.messages
      }
      if (!existing.title && local.title) {
        existing.title = local.title
      }
      sessions.value = sessions.value.filter(session => session.key !== localSessionKey)
      if (activeSessionKey.value === localSessionKey) {
        activeSessionKey.value = serverSessionKey
        activeSession.value = existing
      }
    } else {
      local.id = serverSessionId
      local.key = serverSessionKey
      if (activeSessionKey.value === localSessionKey) {
        activeSessionKey.value = serverSessionKey
        activeSession.value = local
      }
    }

    if (activeSessionKey.value === serverSessionKey) {
      setItemBestEffort(storageKey(), serverSessionKey)
    }
    removeItem(msgsCacheKey(localSessionKey))
    persistSessionsList()
    persistSessionMessages(serverSessionKey)
    return serverSessionKey
  }

  function chatRouteToken(): string {
    return `${activeSessionKey.value || ''}|${getManagementProfile()}`
  }

  async function sendMessage(content: string, attachments?: Attachment[]): Promise<boolean> {
    if ((!content.trim() && !(attachments && attachments.length > 0)) || isStreaming.value) return false

    let createdSessionKey: string | null = null
    if (!activeSession.value) {
      const session = createSession()
      createdSessionKey = session.key
      await switchSession(session.key)
      if (activeSessionKey.value !== createdSessionKey) return false
    }

    // 固定完整提交上下文；任何 await 之后都不能重新读取当前选中会话。
    const startingSessionKey = activeSessionKey.value
    const startingSession = startingSessionKey ? sessionForKey(startingSessionKey) : undefined
    if (!startingSessionKey || !startingSession) return false
    const startingSessionId = startingSession.id
    const startingProfile = startingSession.profile
    const startingRouteToken = chatRouteToken()
    const startingAuthContextVersion = authContextVersion
    const startingModel = startingSession.model || useAppStore().selectedModel || undefined
    const previousTitle = startingSession.title
    const previousUpdatedAt = startingSession.updatedAt
    const isSlashCommand = content.trim().startsWith('/')

    const userMsg: Message = {
      id: uid(),
      role: 'user',
      content: content.trim(),
      timestamp: Date.now(),
      attachments: attachments && attachments.length > 0 ? attachments : undefined,
    }
    addMessage(startingSessionKey, userMsg)
    updateSessionTitle(startingSessionKey)
    // Persist immediately so a refresh before the first SSE event (e.g. the
    // user closes the tab right after sending) still has the user's message
    // and session title in the cache.
    persistSessionMessages(startingSessionKey)
    persistSessionsList()

    const sessionContextDrifted = () =>
      !isCurrentAuthContext(startingAuthContextVersion)
      || activeSessionKey.value !== startingSessionKey
      || chatRouteToken() !== startingRouteToken

    const abortForSessionSwitch = (): false => {
      if (!isCurrentAuthContext(startingAuthContextVersion)) return false
      const target = sessionForKey(startingSessionKey)
      if (target) {
        target.messages = target.messages.filter(message => message.id !== userMsg.id)
        target.title = previousTitle
        target.updatedAt = previousUpdatedAt
        persistSessionMessages(startingSessionKey)
        persistSessionsList()
      }
      return false
    }

    try {
      // Build conversation history from past messages
      const sessionMsgs = getSessionMsgs(startingSessionKey)
      const history: ChatMessage[] = sessionMsgs
        .filter(m => m.id !== userMsg.id && (m.role === 'user' || m.role === 'assistant') && m.content.trim())
        .map(m => ({ role: m.role as 'user' | 'assistant' | 'system', content: m.content }))

      const uploadedAttachments = attachments && attachments.length > 0
          ? await uploadChatFiles(
            attachments.map(att => att.file).filter((file): file is File => !!file),
            startingProfile,
          )
        : []

      if (sessionContextDrifted()) return abortForSessionSwitch()

      const run = await startRun({
        input: content.trim(),
        profile: startingProfile,
        conversation_history: history,
        session_id: startingSessionId,
        model: startingModel,
        attachments: uploadedAttachments,
      })

      if (sessionContextDrifted()) return abortForSessionSwitch()

      const runId = (run as any).run_id || (run as any).id
      if (!runId) {
        addMessage(startingSessionKey, {
          id: uid(),
          role: 'system',
          content: `Error: startRun returned no run ID. Response: ${JSON.stringify(run)}`,
          timestamp: Date.now(),
        })
        persistSessionMessages(startingSessionKey)
        return false
      }

      const sid = adoptServerSessionId(startingSessionKey, run.session_id || startingSessionId)

      // tmux-like resume: persist run_id so refresh/reopen can pick up the
      // working indicator and poll for progress.
      markInFlight(sid, runId)
      runIds.value.set(sid, runId)
      // If we were already polling (e.g. user re-sent while resume was still
      // polling an earlier run), cancel that polling — the new SSE stream is
      // the authoritative live source.
      stopPolling(sid)

      // Helper to clean up this session's stream state
      const cleanup = () => {
        streamStates.value.delete(sid)
        runIds.value.delete(sid)
        if (persistTimer) {
          clearTimeout(persistTimer)
          persistTimer = null
        }
      }

      // Throttle in-flight cache writes so a refresh mid-stream still shows
      // the partial reply. 800ms keeps quota pressure low while guaranteeing
      // at most ~1s of unsaved delta on reload.
      let persistTimer: ReturnType<typeof setTimeout> | null = null
      const schedulePersist = () => {
        if (persistTimer) return
        persistTimer = setTimeout(() => {
          persistTimer = null
          persistSessionMessages(sid)
        }, 800)
      }

      // Listen to SSE events — all closures capture `sid`
      const ctrl = streamRunEvents(
        runId,
        // onEvent
        (evt: RunEvent) => {
          if (!isCurrentAuthContext(startingAuthContextVersion)) return
          switch (evt.event) {
            case 'run.started':
              break

            case 'attempt.started':
              markAgentRunInFlight(sid, evt.agent_run_id)
              addMessage(sid, {
                id: uid(),
                role: 'system',
                content: `开始第 ${evt.attempt_no || 1} 次尝试：${evt.provider || '-'} / ${evt.model || '-'}`,
                timestamp: Date.now(),
              })
              schedulePersist()
              break

            case 'compression.decision':
              if (evt.compressed) {
                addMessage(sid, {
                  id: uid(),
                  role: 'system',
                  content: `已压缩上下文：${evt.estimated_tokens || 0} / ${evt.threshold_tokens || 0} tokens`,
                  timestamp: Date.now(),
                })
                schedulePersist()
              }
              break

            case 'fallback':
              addMessage(sid, {
                id: uid(),
                role: 'system',
                content: `模型切换：${evt.from_provider || '-'} -> ${evt.to_provider || '-'}`,
                timestamp: Date.now(),
              })
              schedulePersist()
              break

            case 'recovery.started':
              addMessage(sid, {
                id: uid(),
                role: 'system',
                content: evt.recovery_type === 'max_steps' ? '达到步数上限，正在收敛总结' : '工具已完成，正在恢复最终答复',
                timestamp: Date.now(),
              })
              schedulePersist()
              break

            case 'message.delta': {
              const msgs = getSessionMsgs(sid)
              const last = msgs[msgs.length - 1]
              if (last?.role === 'assistant' && last.isStreaming) {
                last.content += evt.delta || ''
              } else {
                addMessage(sid, {
                  id: uid(),
                  role: 'assistant',
                  content: evt.delta || '',
                  timestamp: Date.now(),
                  isStreaming: true,
                })
              }
              schedulePersist()
              break
            }

            case 'message.reset': {
              const msgs = getSessionMsgs(sid)
              const last = msgs[msgs.length - 1]
              if (last?.role === 'assistant' && last.isStreaming) {
                msgs.pop()
              }
              schedulePersist()
              break
            }

            case 'reasoning.delta': {
              const msgs = getSessionMsgs(sid)
              const last = msgs[msgs.length - 1]
              if (last?.role === 'assistant' && last.isStreaming) {
                last.reasoning = (last.reasoning || '') + (evt.delta || '')
              } else {
                addMessage(sid, {
                  id: uid(),
                  role: 'assistant',
                  content: '',
                  reasoning: evt.delta || '',
                  timestamp: Date.now(),
                  isStreaming: true,
                })
              }
              schedulePersist()
              break
            }

            case 'tool.started': {
              const msgs = getSessionMsgs(sid)
              const last = msgs[msgs.length - 1]
              if (last?.isStreaming) {
                updateMessage(sid, last.id, { isStreaming: false })
              }
              addMessage(sid, {
                id: uid(),
                role: 'tool',
                content: '',
                timestamp: Date.now(),
                toolName: evt.tool || evt.name,
                toolPreview: evt.preview,
                toolStatus: 'running',
              })
              schedulePersist()
              break
            }

            case 'tool.completed': {
              const msgs = getSessionMsgs(sid)
              const toolMsgs = msgs.filter(
                m => m.role === 'tool' && m.toolStatus === 'running',
              )
              const error = evt.status === 'error' ? (evt.error || evt.preview || 'Tool execution failed') : undefined
              if (toolMsgs.length > 0) {
                const last = toolMsgs[toolMsgs.length - 1]
                updateMessage(sid, last.id, {
                  toolPreview: error || evt.preview,
                  toolResult: error || undefined,
                  toolStatus: error ? 'error' : 'done',
                })
              } else if (error) {
                // 参数校验等失败会直接发送 completed，前端仍需保留可见错误块。
                addMessage(sid, {
                  id: uid(),
                  role: 'tool',
                  content: '',
                  timestamp: Date.now(),
                  toolName: evt.tool || evt.name || 'tool',
                  toolPreview: error,
                  toolResult: error,
                  toolStatus: 'error',
                })
              }
              schedulePersist()
              break
            }

            case 'run.completed': {
              markAgentRunInFlight(sid, evt.agent_run_id)
              const completedSessionId = evt.session_id || sessionForKey(sid)?.id || startingSessionId
              const completedSessionKey = profileSessionIdentity(completedSessionId, startingProfile)
              const msgs = getSessionMsgs(sid)
              const lastMsg = msgs[msgs.length - 1]
              if (lastMsg?.isStreaming) {
                updateMessage(sid, lastMsg.id, { isStreaming: false })
              }
              if (evt.usage) {
                const target = sessionForKey(completedSessionKey) || sessionForKey(sid)
                if (target) {
                  target.inputTokens = evt.usage.input_tokens
                  target.outputTokens = evt.usage.output_tokens
                  target.lastTotalTokens = evt.usage.total_tokens
                }
              }
              if (evt.reasoning && !msgs.some(m => m.role === 'assistant' && m.reasoning?.trim())) {
                const assistant = [...msgs].reverse().find(m => m.role === 'assistant')
                if (assistant && !assistant.reasoning) {
                  assistant.reasoning = evt.reasoning
                }
              }
              cleanup()
              updateSessionTitle(sid)
              // the in-flight marker. If the browser is reloading right now
              // and kills us between the two localStorage writes, we want
              // the next page load to still see in-flight === true (so
              // polling kicks in and recovers) rather than the other way
              // around (cleared in-flight + stale streaming cache = UI stuck).
              persistSessionMessages(sid)
              clearInFlight(sid)
              stopPolling(sid)
              if (completedSessionKey !== sid) {
                const exists = sessionForKey(completedSessionKey)
                if (!exists) {
                  sessions.value.unshift({
                    key: completedSessionKey,
                    id: completedSessionId,
                    profile: startingProfile,
                    title: '',
                    source: 'api_server',
                    messages: [],
                    createdAt: Date.now(),
                    updatedAt: Date.now(),
                  })
                  persistSessionsList()
                }
                if (activeSessionKey.value === sid) {
                  void switchSession(completedSessionKey)
                }
              } else if (isSlashCommand && activeSessionKey.value === sid) {
                void refreshActiveSession()
              }
              break
            }

            case 'run.failed': {
              markAgentRunInFlight(sid, evt.agent_run_id)
              const msgs = getSessionMsgs(sid)
              const lastErr = msgs[msgs.length - 1]
              if (lastErr?.isStreaming) {
                updateMessage(sid, lastErr.id, {
                  isStreaming: false,
                  content: evt.error ? `Error: ${evt.error}` : 'Run failed',
                  role: 'system',
                })
              } else {
                addMessage(sid, {
                  id: uid(),
                  role: 'system',
                  content: evt.error ? `Error: ${evt.error}` : 'Run failed',
                  timestamp: Date.now(),
                })
              }
              msgs.forEach((m, i) => {
                if (m.role === 'tool' && m.toolStatus === 'running') {
                  msgs[i] = { ...m, toolStatus: 'error' }
                }
              })
              cleanup()
              persistSessionMessages(sid)
              clearInFlight(sid)
              stopPolling(sid)
              if (isSlashCommand && activeSessionKey.value === sid) {
                void refreshActiveSession()
              }
              break
            }
          }
        },
        // onDone
        () => {
          if (!isCurrentAuthContext(startingAuthContextVersion)) return
          const msgs = getSessionMsgs(sid)
          const last = msgs[msgs.length - 1]
          if (last?.isStreaming) {
            updateMessage(sid, last.id, { isStreaming: false })
          }
          cleanup()
          updateSessionTitle(sid)
          persistSessionMessages(sid)
        },
        // onError
        // Mobile browsers drop EventSource when the tab backgrounds / screen
        // locks / network flips. The backend run usually completes anyway, so
        // rather than injecting a stale "SSE connection error" bubble we end
        // text streaming and silently re-sync from the server. 工具状态在服务端
        // 确认前保持运行中，避免把未知结果误标为成功。
        (err) => {
          if (!isCurrentAuthContext(startingAuthContextVersion)) return
          console.warn('SSE connection dropped, resyncing from server:', err.message)
          const msgs = getSessionMsgs(sid)
          const last = msgs[msgs.length - 1]
          if (last?.isStreaming) {
            updateMessage(sid, last.id, { isStreaming: false })
          }
          cleanup()
          persistSessionMessages(sid)
          if (sid === activeSessionKey.value) {
            void refreshActiveSession()
          }
          // The run might still be going on the server side (SSE drop doesn't
          // abort it). If we still have an in-flight record, fall back to
          // polling fetchSession to keep the user updated.
          if (readInFlight(sid)) {
            startPolling(sid)
          }
        },
        startingProfile,
      )

      streamStates.value.set(sid, ctrl)
      return true
    } catch (err: any) {
      if (sessionContextDrifted()) return abortForSessionSwitch()
      addMessage(startingSessionKey, {
        id: uid(),
        role: 'system',
        content: `Error: ${err.message}`,
        timestamp: Date.now(),
      })
      persistSessionMessages(startingSessionKey)
      return false
    }
  }

  async function sendSlashCommand(command: string) {
    const text = command.trim()
    if (!text.startsWith('/') || isStreaming.value) return false
    return await sendMessage(text)
  }

  async function stopStreaming() {
    const startingAuthContextVersion = authContextVersion
    const sid = activeSessionKey.value
    if (!sid) return
    const runId = runIds.value.get(sid)
    if (runId) {
      try {
        await cancelRun(runId, profileForSession(sid))
      } catch {
        // ignore best-effort cancel failure
      }
    }
    if (!isCurrentAuthContext(startingAuthContextVersion)) return
    const ctrl = streamStates.value.get(sid)
    if (ctrl) {
      ctrl.abort()
      const msgs = getSessionMsgs(sid)
      const lastMsg = msgs[msgs.length - 1]
      if (lastMsg?.isStreaming) {
        updateMessage(sid, lastMsg.id, { isStreaming: false })
      }
      streamStates.value.delete(sid)
      runIds.value.delete(sid)
      persistSessionMessages(sid)
      clearInFlight(sid)
      stopPolling(sid)
    }
  }

  /** 页面重新可见时恢复活动会话；Store 销毁后必须移除该监听器。 */
  function handleVisibilityChange(): void {
    if (document.visibilityState === 'visible' && activeSessionKey.value && !isStreaming.value) {
      void refreshActiveSession()
      if (readInFlight(activeSessionKey.value)) {
        startPolling(activeSessionKey.value)
      }
    }
  }

  // Tab visibility: re-sync when returning to foreground
  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', handleVisibilityChange)
    onScopeDispose(() => document.removeEventListener('visibilitychange', handleVisibilityChange))
  }

  return {
    sessions,
    activeSessionKey,
    activeSessionId,
    activeSession,
    focusMessageId,
    messages,
    isStreaming,
    isRunActive,
    isSessionLive,
    isLoadingSessions,
    sessionsLoaded,
    isLoadingMessages,

    newChat,
    switchSession,
    switchSessionModel,
    deleteSession,
    sendMessage,
    sendSlashCommand,
    stopStreaming,
    loadSessions,
    refreshActiveSession,
  }
})
