import { useEffect, useMemo, useReducer, useRef, useState } from 'react'
import type { FormEvent, KeyboardEvent } from 'react'
import { TuiJsonRpcClient } from './tuiClient'
import { createHistoryItem, initialTuiState, tuiReducer } from './tuiReducer'
import { handleOsc52, openSafeUrl, readClipboard, sanitizeInputForDisplay, writeClipboard } from './tuiSafety'
import type { BusyPolicy, TuiApproval, TuiSession } from './tuiTypes'
import './tui.css'

export function TuiApp() {
  const [state, dispatch] = useReducer(tuiReducer, initialTuiState)
  const [input, setInput] = useState('')
  const [activePanel, setActivePanel] = useState<'model' | 'session' | 'command' | null>(null)
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
        setActivePanel('command')
      } else if (key === 'm') {
        event.preventDefault()
        setActivePanel('model')
      } else if (key === 'n') {
        event.preventDefault()
        createSession()
      } else if (key === 'l') {
        event.preventDefault()
        dispatch({ type: 'clear' })
      } else if (event.shiftKey && key === 'c') {
        event.preventDefault()
        void copyLastReply()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  })

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
    void clientRef.current?.request(method, payload)

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
    }
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
    void clientRef.current?.request('session.resume', { sessionId, after_seq: 0 })
  }

  function switchModel(modelId: string) {
    dispatch({ type: 'model', payload: { activeModelId: modelId } })
    setActivePanel(null)
    void clientRef.current?.request('model.switch', { sessionId: state.activeSessionId, model: modelId })
  }

  function runCommand(command: string) {
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
    })
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
          <PanelButton active={activePanel === 'session'} label="会话" shortcut="Ctrl+N" onClick={() => setActivePanel(activePanel === 'session' ? null : 'session')} />
          <PanelButton active={activePanel === 'model'} label="模型" shortcut="Ctrl+M" onClick={() => setActivePanel(activePanel === 'model' ? null : 'model')} />
          <PanelButton active={activePanel === 'command'} label="命令" shortcut="Ctrl+K" onClick={() => setActivePanel(activePanel === 'command' ? null : 'command')} />
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
                <pre>{item.text}</pre>
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
              <SessionPanel sessions={state.sessions} activeSessionId={state.activeSessionId} onSwitch={switchSession} onCreate={createSession} />
            ) : null}
            {activePanel === 'model' ? (
              <ModelPanel models={state.models} activeModelId={state.activeModelId} onSwitch={switchModel} />
            ) : null}
            {activePanel === 'command' ? (
              <CommandPanel commands={state.commands} onRun={runCommand} />
            ) : null}
          </aside>
        ) : null}
      </div>

      <footer className="tui-statusbar">
        <span>{state.busy ? '运行中' : '空闲'}</span>
        <span>{state.queuedInputs.length > 0 ? `队列 ${state.queuedInputs.length}` : '队列空'}</span>
        <span>{activeSession?.cwd || 'workspace'}</span>
        <span>Ctrl+K 命令面板</span>
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

function SessionPanel(props: { sessions: TuiSession[]; activeSessionId: string; onSwitch: (id: string) => void; onCreate: () => void }) {
  return (
    <>
      <PanelHeader title="会话面板" action="新建" onAction={props.onCreate} />
      <div className="tui-panel-list">
        {props.sessions.map((session) => (
          <button className={session.id === props.activeSessionId ? 'active' : ''} type="button" key={session.id} onClick={() => props.onSwitch(session.id)}>
            <span>{session.title}</span>
            <small>{session.cwd}</small>
          </button>
        ))}
      </div>
    </>
  )
}

function ModelPanel(props: { models: { id: string; label: string; provider: string; context: string }[]; activeModelId: string; onSwitch: (id: string) => void }) {
  return (
    <>
      <PanelHeader title="模型面板" />
      <div className="tui-panel-list">
        {props.models.map((model) => (
          <button className={model.id === props.activeModelId ? 'active' : ''} type="button" key={model.id} onClick={() => props.onSwitch(model.id)}>
            <span>{model.label}</span>
            <small>{model.provider} · {model.context}</small>
          </button>
        ))}
      </div>
    </>
  )
}

function CommandPanel(props: { commands: { name: string; description: string; hotkey?: string }[]; onRun: (command: string) => void }) {
  return (
    <>
      <PanelHeader title="命令面板" />
      <div className="tui-panel-list">
        {props.commands.map((command) => (
          <button type="button" key={command.name} onClick={() => props.onRun(command.name)}>
            <span>{command.name}</span>
            <small>{command.description}{command.hotkey ? ` · ${command.hotkey}` : ''}</small>
          </button>
        ))}
      </div>
    </>
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
