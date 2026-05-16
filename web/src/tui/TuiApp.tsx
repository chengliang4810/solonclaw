import { useEffect, useMemo, useReducer, useRef, useState } from 'react'
import type { JSX } from 'react'
import type { FormEvent, KeyboardEvent } from 'react'
import { TuiJsonRpcClient } from './tuiClient'
import { createHistoryItem, initialTuiState, tuiReducer } from './tuiReducer'
import { handleOsc52, openSafeUrl, readClipboard, sanitizeInputForDisplay, writeClipboard } from './tuiSafety'
import type { BusyPolicy, TuiApproval, TuiIntegrationKind, TuiIntegrationSnapshot, TuiRunTimelineItem, TuiSession, VirtualHistoryItem } from './tuiTypes'
import './tui.css'

export function TuiApp() {
  const [state, dispatch] = useReducer(tuiReducer, initialTuiState)
  const [input, setInput] = useState('')
  const [activePanel, setActivePanel] = useState<'model' | 'session' | 'command' | 'integration' | null>(null)
  const [panelSearch, setPanelSearch] = useState('')
  const [toast, setToast] = useState('')
  const clientRef = useRef<TuiJsonRpcClient | null>(null)
  const historyRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<HTMLTextAreaElement | null>(null)

  const activeSession = useMemo(
    () => state.sessions.find((session) => session.id === state.activeSessionId) || state.sessions[0],
    [state.activeSessionId, state.sessions],
  )
  const activeModel = useMemo(
    () => state.models.find((model) => model.id === state.activeModelId) || state.models[0],
    [state.activeModelId, state.models],
  )
  const pendingApprovals = state.approvals.filter((approval) => approval.status === 'pending')

  useEffect(() => {
    const client = new TuiJsonRpcClient((event) => dispatch(event))
    clientRef.current = client
    client.connect()
    return () => client.close()
  }, [])

  useEffect(() => {
    historyRef.current?.scrollTo({ top: historyRef.current.scrollHeight, behavior: 'smooth' })
  }, [state.history.length])

  useEffect(() => {
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      const modifier = event.ctrlKey || event.metaKey
      if (!modifier) {
        if (event.key === 'Escape') setActivePanel(null)
        return
      }
      const key = event.key.toLowerCase()
      if (key === 'k') {
        event.preventDefault()
        openPanel('command')
      } else if (key === 'm') {
        event.preventDefault()
        openPanel('model')
      } else if (key === 'n') {
        event.preventDefault()
        createSession()
      } else if (key === 'l') {
        event.preventDefault()
        dispatch({ type: 'clear' })
      } else if (key === 'i') {
        event.preventDefault()
        openPanel('integration')
      } else if (event.shiftKey && key === 'c') {
        event.preventDefault()
        void copyLastReply()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  })

  useEffect(() => {
    if (state.connection !== 'connected' || !state.activeSessionId) return
    const afterSeq = state.lastSeqBySession[state.activeSessionId] || 0
    void clientRef.current?.request('run.replay', { sessionId: state.activeSessionId, after_seq: afterSeq })
    void clientRef.current?.request('approval.list', { sessionId: state.activeSessionId })
    void clientRef.current?.request('integration.snapshot', { sessionId: state.activeSessionId })
  }, [state.connection, state.activeSessionId])

  function submitInput(event?: FormEvent) {
    event?.preventDefault()
    const text = input.trim()
    if (!text) return

    if (state.busy) {
      if (state.busyPolicy === 'queue') {
        dispatch({ type: 'busy', payload: { busy: true, queuedInputs: [...state.queuedInputs, text] } })
        setInput('')
        void clientRef.current?.request('input.queue', {
          sessionId: state.activeSessionId,
          model: state.activeModelId,
          input: text,
          busy_mode: 'queue',
        })
        showToast('已加入输入队列')
        return
      }
      if (state.busyPolicy === 'steer') {
        dispatch({ type: 'busy', payload: { busy: true } })
        setInput('')
        void clientRef.current?.request('input.steer', {
          sessionId: state.activeSessionId,
          input: text,
          busy_mode: 'steer',
        })
        showToast('已作为转向指令发送')
        return
      }
      void clientRef.current?.request('input.interrupt', { sessionId: state.activeSessionId })
    }

    dispatch({ type: 'history', payload: createHistoryItem('user', sanitizeInputForDisplay(text), state.activeSessionId) })
    dispatch({ type: 'busy', payload: { busy: true } })
    setInput('')

    const method = text.startsWith('/') ? 'slash.run' : 'input.send'
    const payload = {
      sessionId: state.activeSessionId,
      model: state.activeModelId,
      command: text,
      input: text,
      busy_mode: state.busyPolicy,
    }
    if (text.startsWith('/')) dispatch({ type: 'command', payload: { recentCommand: text.split(/\s+/, 1)[0] } })
    void clientRef.current?.request(method, payload).catch((error) => {
      dispatch({
        type: 'history',
        payload: createHistoryItem('system', `发送失败：${error instanceof Error ? error.message : String(error)}`, state.activeSessionId),
      })
      dispatch({ type: 'busy', payload: { busy: false } })
    })

    if (!clientRef.current?.isConnected()) {
      window.setTimeout(() => {
        dispatch({
          type: 'history',
          payload: createHistoryItem('system', 'WebSocket JSON-RPC 暂未连接。本轮输入已保留在本地虚拟历史中。', state.activeSessionId),
        })
        dispatch({ type: 'busy', payload: { busy: false } })
      }, 260)
    }
  }

  function onInputKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
      submitInput()
      return
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'v') {
      const text = input.trim()
      if (looksLikePath(text)) {
        showToast('检测到路径文本；可直接发送，或换行补充说明')
      }
    }
  }

  function openPanel(panel: 'model' | 'session' | 'command' | 'integration') {
    setPanelSearch('')
    setActivePanel(panel)
  }

  function createSession() {
    void clientRef.current?.request('session.start', {
      title: `会话 ${state.sessions.length + 1}`,
      model: state.activeModelId,
      busy_mode: state.busyPolicy,
    }).catch(() => {
      const id = `local-${Date.now()}`
      const session: TuiSession = {
        id,
        title: `会话 ${state.sessions.length + 1}`,
        cwd: activeSession?.cwd || 'workspace',
        model: state.activeModelId,
        createdAt: Date.now(),
        active: true,
      }
      dispatch({
        type: 'session',
        payload: {
          activeSessionId: id,
          sessions: [...state.sessions.map((item) => ({ ...item, active: false })), session],
        },
      })
    })
  }

  function switchSession(sessionId: string) {
    dispatch({
      type: 'session',
      payload: {
        activeSessionId: sessionId,
        sessions: state.sessions,
      },
    })
    setActivePanel(null)
    const afterSeq = state.lastSeqBySession[sessionId] || 0
    void clientRef.current?.request('session.resume', { sessionId, after_seq: afterSeq })
  }

  function switchModel(modelId: string) {
    dispatch({ type: 'model', payload: { activeModelId: modelId } })
    setActivePanel(null)
    void clientRef.current?.request('model.switch', { sessionId: state.activeSessionId, model: modelId })
  }

  function runCommand(command: string) {
    dispatch({ type: 'command', payload: { recentCommand: command } })
    setInput(command)
    setActivePanel(null)
    inputRef.current?.focus()
  }

  function setBusyPolicy(policy: BusyPolicy) {
    dispatch({ type: 'busy', payload: { busy: state.busy, policy } })
    void clientRef.current?.notify('terminal.resize', { sessionId: state.activeSessionId, policy })
  }

  function resolveApproval(approval: TuiApproval, action: 'approve' | 'deny') {
    dispatch({ type: 'approval', payload: { ...approval, status: action === 'approve' ? 'approved' : 'denied' } })
    void clientRef.current?.request(action === 'approve' ? 'approval.approve' : 'approval.deny', {
      sessionId: state.activeSessionId,
      selector: approval.id,
      scope: 'once',
    }).catch((error) => showToast(error instanceof Error ? error.message : String(error)))
  }

  async function copyLastReply() {
    const item = [...state.history].reverse().find((entry) => entry.role === 'assistant' || entry.role === 'system')
    if (!item) {
      showToast('没有可复制内容')
      return
    }
    showToast(await writeClipboard(item.text) ? '已复制最近回复' : '浏览器不允许写入剪贴板')
  }

  async function pasteClipboard() {
    const text = await readClipboard()
    if (!text) {
      showToast('浏览器不允许读取剪贴板')
      return
    }
    setInput((value) => `${value}${value ? '\n' : ''}${text}`)
    if (looksLikePath(text)) {
      showToast('已粘贴路径文本')
    }
  }

  async function copyOsc52() {
    const result = await handleOsc52(input)
    showToast(result.ok ? 'OSC52 内容已写入剪贴板' : result.error || 'OSC52 处理失败')
  }

  function showToast(text: string) {
    setToast(text)
    window.setTimeout(() => setToast(''), 2200)
  }

  return (
    <section className="tui-shell" aria-label="React 终端 UI">
      <header className="tui-topbar">
        <div className="tui-title-block">
          <span className="tui-kicker">React TUI</span>
          <h1>终端工作台</h1>
        </div>
        <div className="tui-status-strip">
          <StatusPill label="连接" value={connectionText(state.connection, state.reconnectAttempt)} tone={state.connection === 'connected' ? 'good' : 'warn'} />
          <StatusPill label="模型" value={activeModel?.label || state.activeModelId} />
          <StatusPill label="会话" value={activeSession?.title || state.activeSessionId} />
          <StatusPill label="审批" value={`${pendingApprovals.length}`} tone={pendingApprovals.length > 0 ? 'danger' : 'good'} />
        </div>
      </header>

      <div className="tui-workspace">
        <aside className="tui-sidebar">
          <PanelButton active={activePanel === 'session'} label="会话" shortcut="Ctrl+N" onClick={() => activePanel === 'session' ? setActivePanel(null) : openPanel('session')} />
          <PanelButton active={activePanel === 'model'} label="模型" shortcut="Ctrl+M" onClick={() => activePanel === 'model' ? setActivePanel(null) : openPanel('model')} />
          <PanelButton active={activePanel === 'command'} label="命令" shortcut="Ctrl+K" onClick={() => activePanel === 'command' ? setActivePanel(null) : openPanel('command')} />
          <PanelButton active={activePanel === 'integration'} label="状态" shortcut="Ctrl+I" onClick={() => activePanel === 'integration' ? setActivePanel(null) : openPanel('integration')} />
          <div className="tui-policy">
            <span>忙碌输入</span>
            <select value={state.busyPolicy} onChange={(event) => setBusyPolicy(event.currentTarget.value as BusyPolicy)}>
              <option value="queue">排队</option>
              <option value="steer">转向</option>
              <option value="interrupt">打断</option>
            </select>
          </div>
          <button className="tui-secondary-button" type="button" onClick={() => dispatch({ type: 'clear' })}>
            清空历史
          </button>
        </aside>

        <main className="tui-main">
          <div className="tui-history" ref={historyRef}>
            {state.history.map((item) => (
              <article className={`tui-message ${item.role}`} key={item.id}>
                <div className="tui-message-meta">
                  <span>{roleLabel(item.role)}</span>
                  <time>{formatTime(item.createdAt)}</time>
                </div>
                <MessageBody item={item} onCopy={showToast} />
                {item.urls && item.urls.length > 0 ? (
                  <div className="tui-url-row">
                    {item.urls.map((url) => (
                      <button type="button" key={url} onClick={() => openSafeUrl(url)}>
                        打开安全链接
                      </button>
                    ))}
                  </div>
                ) : null}
              </article>
            ))}
          </div>

          {pendingApprovals.length > 0 ? (
            <section className="tui-approval-rail" aria-label="待审批">
              {pendingApprovals.map((approval) => (
                <ApprovalCard key={approval.id} approval={approval} onResolve={resolveApproval} />
              ))}
            </section>
          ) : null}

          <form className="tui-input-bar" onSubmit={submitInput}>
            <textarea
              ref={inputRef}
              value={input}
              onChange={(event) => setInput(event.currentTarget.value)}
              onKeyDown={onInputKeyDown}
              placeholder={state.busy ? busyPlaceholder(state.busyPolicy) : '输入消息或 / 命令，Ctrl+Enter 发送'}
              rows={3}
            />
            <div className="tui-input-actions">
              <button type="button" onClick={pasteClipboard}>粘贴</button>
              <button type="button" onClick={copyLastReply}>复制最近回复</button>
              <button type="button" onClick={copyOsc52}>OSC52</button>
              <button className="tui-primary-button" type="submit" disabled={!input.trim()}>
                发送
              </button>
            </div>
          </form>
        </main>

        {activePanel ? (
          <aside className="tui-panel">
            {activePanel === 'session' ? (
              <SessionPanel search={panelSearch} setSearch={setPanelSearch} sessions={state.sessions} recentSessions={state.recentSessions} activeSessionId={state.activeSessionId} onSwitch={switchSession} onCreate={createSession} />
            ) : null}
            {activePanel === 'model' ? (
              <ModelPanel search={panelSearch} setSearch={setPanelSearch} models={state.models} recentModels={state.recentModels} activeModelId={state.activeModelId} onSwitch={switchModel} />
            ) : null}
            {activePanel === 'command' ? (
              <CommandPanel search={panelSearch} setSearch={setPanelSearch} commands={state.commands} recentCommands={state.recentCommands} onRun={runCommand} />
            ) : null}
            {activePanel === 'integration' ? (
              <IntegrationPanel
                snapshots={state.integrations}
                onRefresh={(kind) => void clientRef.current?.request(`${kind}.snapshot`, { sessionId: state.activeSessionId })}
                onRefreshAll={() => void clientRef.current?.request('integration.snapshot', { sessionId: state.activeSessionId })}
              />
            ) : null}
            <TimelinePanel items={state.timeline.slice(-12)} />
          </aside>
        ) : null}
      </div>

      <footer className="tui-statusbar">
        <span>{state.busy ? '运行中' : '空闲'}</span>
        <span>{state.queuedInputs.length > 0 ? `队列 ${state.queuedInputs.length}` : '队列空'}</span>
        <span>{activeSession?.cwd || 'workspace'}</span>
        <span>Ctrl+K 命令面板</span>
        <span>Ctrl+I 集成状态</span>
        <span>Ctrl+Shift+C 复制最近回复</span>
      </footer>

      {toast ? <div className="tui-toast">{toast}</div> : null}
    </section>
  )
}

function StatusPill(props: { label: string; value: string; tone?: 'good' | 'warn' | 'danger' }) {
  return (
    <span className={`tui-status-pill ${props.tone || ''}`}>
      <span>{props.label}</span>
      <strong>{props.value}</strong>
    </span>
  )
}

function PanelButton(props: { active: boolean; label: string; shortcut: string; onClick: () => void }) {
  return (
    <button className={`tui-panel-button ${props.active ? 'active' : ''}`} type="button" onClick={props.onClick}>
      <span>{props.label}</span>
      <kbd>{props.shortcut}</kbd>
    </button>
  )
}

function ApprovalCard(props: { approval: TuiApproval; onResolve: (approval: TuiApproval, action: 'approve' | 'deny') => void }) {
  return (
    <article className={`tui-approval-card ${props.approval.risk}`}>
      <div>
        <span className="tui-approval-label">审批</span>
        <h2>{props.approval.title}</h2>
        <p>{props.approval.reason}</p>
      </div>
      <pre>{props.approval.command}</pre>
      <div className="tui-approval-actions">
        <button type="button" onClick={() => props.onResolve(props.approval, 'deny')}>拒绝</button>
        <button type="button" onClick={() => props.onResolve(props.approval, 'approve')}>批准本次</button>
      </div>
    </article>
  )
}

function MessageBody(props: { item: VirtualHistoryItem; onCopy: (text: string) => void }) {
  const parts = splitMarkdown(props.item.text)
  return (
    <div className="tui-markdown">
      {parts.map((part, index) => part.code ? (
        <CodeBlock key={`${props.item.id}-${index}`} language={part.language} code={part.text} onCopy={props.onCopy} />
      ) : (
        <MarkdownText key={`${props.item.id}-${index}`} text={part.text} />
      ))}
    </div>
  )
}

function MarkdownText(props: { text: string }) {
  const lines = props.text.split('\n')
  return (
    <>
      {lines.map((line, index) => {
        const key = `${index}-${line}`
        if (line.startsWith('### ')) return <h4 key={key}>{line.slice(4)}</h4>
        if (line.startsWith('## ')) return <h3 key={key}>{line.slice(3)}</h3>
        if (line.startsWith('# ')) return <h2 key={key}>{line.slice(2)}</h2>
        if (/^\s*[-*]\s+/.test(line)) return <p className="tui-md-list" key={key}>{line.replace(/^\s*[-*]\s+/, '• ')}</p>
        if (/^\s*\d+\.\s+/.test(line)) return <p className="tui-md-list" key={key}>{line.trim()}</p>
        if (!line.trim()) return <br key={key} />
        return <p key={key}>{renderInlineMarkdown(line)}</p>
      })}
    </>
  )
}

function CodeBlock(props: { language?: string; code: string; onCopy: (text: string) => void }) {
  return (
    <pre className="tui-code-block">
      <div className="tui-code-header">
        <span>{props.language || 'text'}</span>
        <button type="button" onClick={() => void writeClipboard(props.code).then((ok) => props.onCopy(ok ? '代码已复制' : '浏览器不允许写入剪贴板'))}>复制</button>
      </div>
      <code>{props.code}</code>
    </pre>
  )
}

function SessionPanel(props: { search: string; setSearch: (value: string) => void; sessions: TuiSession[]; recentSessions: string[]; activeSessionId: string; onSwitch: (id: string) => void; onCreate: () => void }) {
  const sessions = filterSessions(props.sessions, props.search, props.recentSessions)
  return (
    <>
      <PanelHeader title="会话面板" action="新建" onAction={props.onCreate} />
      <PanelSearch value={props.search} onChange={props.setSearch} placeholder="搜索会话、分支或模型" />
      <div className="tui-panel-list">
        {sessions.map((session) => (
          <button className={session.id === props.activeSessionId ? 'active' : ''} type="button" key={session.id} onClick={() => props.onSwitch(session.id)}>
            <span>{session.title}</span>
            <small>{session.branch || 'main'} · {session.model || 'default'} · {session.cwd}</small>
          </button>
        ))}
      </div>
    </>
  )
}

function ModelPanel(props: { search: string; setSearch: (value: string) => void; models: { id: string; label: string; provider: string; context: string }[]; recentModels: string[]; activeModelId: string; onSwitch: (id: string) => void }) {
  const models = filterModels(props.models, props.search, props.recentModels)
  return (
    <>
      <PanelHeader title="模型面板" />
      <PanelSearch value={props.search} onChange={props.setSearch} placeholder="搜索模型、提供方或协议" />
      <div className="tui-panel-list">
        {models.map((model) => (
          <button className={model.id === props.activeModelId ? 'active' : ''} type="button" key={model.id} onClick={() => props.onSwitch(model.id)}>
            <span>{model.label}</span>
            <small>{model.provider} · {model.context || '运行时默认'}{props.recentModels.includes(model.id) ? ' · 最近使用' : ''}</small>
          </button>
        ))}
      </div>
    </>
  )
}

function CommandPanel(props: { search: string; setSearch: (value: string) => void; commands: { name: string; description: string; hotkey?: string }[]; recentCommands: string[]; onRun: (command: string) => void }) {
  const commands = filterCommands(props.commands, props.search, props.recentCommands)
  return (
    <>
      <PanelHeader title="命令面板" />
      <PanelSearch value={props.search} onChange={props.setSearch} placeholder="搜索命令或说明" />
      <div className="tui-panel-list">
        {commands.map((command) => (
          <button type="button" key={command.name} onClick={() => props.onRun(command.name)}>
            <span>{command.name}</span>
            <small>{command.description}{command.hotkey ? ` · ${command.hotkey}` : ''}{props.recentCommands.includes(command.name) ? ' · 最近使用' : ''}</small>
          </button>
        ))}
      </div>
    </>
  )
}

function IntegrationPanel(props: { snapshots: Record<string, TuiIntegrationSnapshot>; onRefresh: (kind: TuiIntegrationKind) => void; onRefreshAll: () => void }) {
  const kinds: TuiIntegrationKind[] = ['cron', 'kanban', 'mcp', 'acp']
  return (
    <>
      <PanelHeader title="集成状态" action="刷新" onAction={props.onRefreshAll} />
      <div className="tui-integration-grid">
        {kinds.map((kind) => {
          const snapshot = props.snapshots[kind] || emptyIntegration(kind)
          return (
            <article className={`tui-integration-card ${snapshot.status}`} key={kind}>
              <header>
                <div>
                  <h3>{snapshot.title}</h3>
                  <small>{statusText(snapshot.status)} · {formatTime(snapshot.updatedAt)}</small>
                </div>
                <button type="button" onClick={() => props.onRefresh(kind)}>刷新</button>
              </header>
              <p>{snapshot.summary || '等待网关推送状态'}</p>
              <MetricStrip metrics={snapshot.metrics} />
              {snapshot.error ? <p className="tui-integration-error">{snapshot.error}</p> : null}
              {snapshot.items.length > 0 ? (
                <div className="tui-integration-items">
                  {snapshot.items.slice(0, 6).map((item) => (
                    <div className="tui-integration-item" key={`${kind}-${item.id}`}>
                      <span>{item.title}</span>
                      <small>{[item.status, item.meta, typeof item.toolCount === 'number' ? `工具 ${item.toolCount}` : ''].filter(Boolean).join(' · ')}</small>
                    </div>
                  ))}
                </div>
              ) : null}
            </article>
          )
        })}
      </div>
    </>
  )
}

function MetricStrip(props: { metrics: Record<string, number | string | boolean | null> }) {
  const entries = Object.entries(props.metrics).filter(([, value]) => value !== null && value !== '')
  if (entries.length === 0) return null
  return (
    <dl className="tui-metrics">
      {entries.slice(0, 6).map(([key, value]) => (
        <div key={key}>
          <dt>{metricLabel(key)}</dt>
          <dd>{String(value)}</dd>
        </div>
      ))}
    </dl>
  )
}

function TimelinePanel(props: { items: TuiRunTimelineItem[] }) {
  if (props.items.length === 0) return null
  return (
    <section className="tui-timeline">
      <h2>运行事件</h2>
      {props.items.map((item) => (
        <article className={`tui-timeline-item ${item.severity || 'info'}`} key={`${item.id}-${item.seq || ''}`}>
          <strong>{item.title}</strong>
          <small>{item.status || item.kind} · {formatTime(item.createdAt)}</small>
          {item.detail ? <p>{item.detail}</p> : null}
        </article>
      ))}
    </section>
  )
}

function PanelSearch(props: { value: string; onChange: (value: string) => void; placeholder: string }) {
  return (
    <input
      className="tui-panel-search"
      value={props.value}
      onChange={(event) => props.onChange(event.currentTarget.value)}
      placeholder={props.placeholder}
    />
  )
}

function PanelHeader(props: { title: string; action?: string; onAction?: () => void }) {
  return (
    <header className="tui-panel-header">
      <h2>{props.title}</h2>
      {props.action ? <button type="button" onClick={props.onAction}>{props.action}</button> : null}
    </header>
  )
}

function connectionText(state: string, reconnectAttempt: number): string {
  if (state === 'connected') return '已连接'
  if (state === 'reconnecting') return `重连 ${reconnectAttempt}`
  if (state === 'connecting') return '连接中'
  if (state === 'closed') return '已关闭'
  return '待连接'
}

function roleLabel(role: string): string {
  if (role === 'user') return '用户'
  if (role === 'assistant') return '助手'
  if (role === 'tool') return '工具'
  if (role === 'approval') return '审批'
  return '系统'
}

function formatTime(value: number): string {
  return new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function busyPlaceholder(policy: BusyPolicy): string {
  if (policy === 'queue') return '当前忙碌：新输入会进入队列'
  if (policy === 'steer') return '当前忙碌：发送会作为转向指令'
  return '当前忙碌：发送会先打断当前任务'
}

function emptyIntegration(kind: TuiIntegrationKind): TuiIntegrationSnapshot {
  return {
    kind,
    title: integrationTitle(kind),
    status: 'unknown',
    available: false,
    summary: '',
    metrics: {},
    items: [],
    updatedAt: Date.now(),
  }
}

function integrationTitle(kind: TuiIntegrationKind): string {
  if (kind === 'cron') return '定时任务'
  if (kind === 'kanban') return '看板'
  if (kind === 'mcp') return 'MCP'
  return 'ACP'
}

function statusText(status: string): string {
  if (status === 'ready') return '就绪'
  if (status === 'running') return '运行中'
  if (status === 'active') return '活跃'
  if (status === 'attention') return '需关注'
  if (status === 'disabled') return '未启用'
  if (status === 'error') return '错误'
  if (status === 'due') return '待触发'
  if (status === 'idle') return '空闲'
  if (status === 'unavailable') return '不可用'
  return status || '未知'
}

function metricLabel(key: string): string {
  const labels: Record<string, string> = {
    total: '总数',
    active: '活跃',
    paused: '暂停',
    completed: '完成',
    due: '待触发',
    ready: '就绪',
    running: '运行',
    blocked: '阻塞',
    done: '完成',
    servers: '服务',
    enabled_servers: '启用',
    tool_count: '工具',
    method_count: '方法',
    command_count: '命令',
    error: '错误',
  }
  return labels[key] || key.replace(/_/g, ' ')
}

function splitMarkdown(text: string): { text: string; code: boolean; language?: string }[] {
  const parts: { text: string; code: boolean; language?: string }[] = []
  const pattern = /```([A-Za-z0-9_+.-]*)\n?([\s\S]*?)```/g
  let lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) {
      parts.push({ text: text.slice(lastIndex, match.index), code: false })
    }
    parts.push({ text: match[2] || '', code: true, language: match[1] || 'text' })
    lastIndex = pattern.lastIndex
  }
  if (lastIndex < text.length) {
    parts.push({ text: text.slice(lastIndex), code: false })
  }
  return parts.length ? parts : [{ text, code: false }]
}

function renderInlineMarkdown(line: string): Array<string | JSX.Element> {
  const parts: Array<string | JSX.Element> = []
  const pattern = /(`[^`]+`|\*\*[^*]+\*\*)/g
  let lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = pattern.exec(line)) !== null) {
    if (match.index > lastIndex) parts.push(line.slice(lastIndex, match.index))
    const token = match[0]
    if (token.startsWith('`')) {
      parts.push(<code key={`${match.index}-${token}`}>{token.slice(1, -1)}</code>)
    } else {
      parts.push(<strong key={`${match.index}-${token}`}>{token.slice(2, -2)}</strong>)
    }
    lastIndex = pattern.lastIndex
  }
  if (lastIndex < line.length) parts.push(line.slice(lastIndex))
  return parts
}

function filterSessions(sessions: TuiSession[], search: string, recent: string[]): TuiSession[] {
  return rankAndFilter(sessions, search, recent, (session) => `${session.title} ${session.id} ${session.branch || ''} ${session.model} ${session.cwd}`)
}

function filterModels<T extends { id: string; label: string; provider: string; context: string }>(models: T[], search: string, recent: string[]): T[] {
  return rankAndFilter(models, search, recent, (model) => `${model.id} ${model.label} ${model.provider} ${model.context}`)
}

function filterCommands<T extends { name: string; description: string }>(commands: T[], search: string, recent: string[]): T[] {
  return rankAndFilter(commands, search, recent, (command) => `${command.name} ${command.description}`)
}

function rankAndFilter<T extends { id?: string; name?: string }>(items: T[], search: string, recent: string[], text: (item: T) => string): T[] {
  const query = search.trim().toLowerCase()
  return [...items]
    .filter((item) => !query || text(item).toLowerCase().includes(query))
    .sort((left, right) => recentScore(right, recent) - recentScore(left, recent))
}

function recentScore(item: { id?: string; name?: string }, recent: string[]): number {
  const key = item.id || item.name || ''
  const index = recent.indexOf(key)
  return index < 0 ? -100 : 100 - index
}

function looksLikePath(text: string): boolean {
  const value = text.trim()
  return /^[A-Za-z]:[\\/]/.test(value)
    || /^~[\\/]/.test(value)
    || /^\.{1,2}[\\/]/.test(value)
    || /^\/[\w.-]/.test(value)
}
