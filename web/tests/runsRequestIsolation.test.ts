import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

type RequestKind = 'runs' | 'tree' | 'checkpoints' | 'detail' | 'recap' | 'trajectory' | 'active'
  | 'recoverable' | 'checkpoint' | 'control' | 'subcontrol' | 'rollback'

interface PendingRequest {
  resolve: (value: any) => void
  reject: (error: Error) => void
}

interface RunsMocks {
  request: (kind: RequestKind, id: string) => Promise<any>
}

declare global {
  var __RUNS_VIEW_MOCKS__: RunsMocks
}

/** 等待异步 continuation 创建下一批请求。 */
async function flush(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
}

const source = readFileSync(new URL('../src/views/solonclaw/RunsView.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)?.[1]
assert.ok(script, 'RunsView should contain a TypeScript setup script')

const testsDirectory = dirname(fileURLToPath(import.meta.url))
const temporaryDirectory = mkdtempSync(join(testsDirectory, '.tmp-runs-isolation-'))
const modulePath = join(temporaryDirectory, 'runs-view-test-module.ts')
const originalConsoleError = console.error

try {
  console.error = () => {}
  const transformed = script
    .replace(/import[\s\S]*?from ['"][^'"]+['"]\n/g, '')
    .replace("const { t } = useI18n()", "const t = (key: string) => key")
    .replace(
      'const sessions = ref<any[]>([])',
      `type AgentRun = any
type AgentRunEvent = any
type RunControlCommand = any
type RunRecovery = any
type SubagentRun = any
type ToolCall = any
let unmountCallback = () => {}
const onMounted = () => {}
const onUnmounted = (callback: () => void) => { unmountCallback = callback }
const fetchSessions = async () => []
const fetchSessionRuns = (id: string) => globalThis.__RUNS_VIEW_MOCKS__.request('runs', id)
const fetchSessionTree = (id: string) => globalThis.__RUNS_VIEW_MOCKS__.request('tree', id)
const fetchSessionCheckpoints = (id: string) => globalThis.__RUNS_VIEW_MOCKS__.request('checkpoints', id)
const fetchRunDetail = (id: string) => globalThis.__RUNS_VIEW_MOCKS__.request('detail', id)
const fetchSessionRecap = (id: string) => globalThis.__RUNS_VIEW_MOCKS__.request('recap', id)
const fetchSessionTrajectory = (id: string) => globalThis.__RUNS_VIEW_MOCKS__.request('trajectory', id)
const fetchActiveSubagents = () => globalThis.__RUNS_VIEW_MOCKS__.request('active', '')
const fetchRecoverableRuns = () => globalThis.__RUNS_VIEW_MOCKS__.request('recoverable', '')
const fetchCheckpointPreview = (id: string) => globalThis.__RUNS_VIEW_MOCKS__.request('checkpoint', id)
const controlRun = (id: string, command: string) => globalThis.__RUNS_VIEW_MOCKS__.request('control', id + ':' + command)
const controlSubagent = (id: string, command: string) => globalThis.__RUNS_VIEW_MOCKS__.request('subcontrol', id + ':' + command)
const rollbackCheckpoint = (id: string) => globalThis.__RUNS_VIEW_MOCKS__.request('rollback', id)
const successMessages: string[] = []
const errorMessages: string[] = []
const message = {
  success: (value: string) => successMessages.push(value),
  error: (value: string) => errorMessages.push(value),
}
const sessions = ref<any[]>([])`,
    )
    .concat('\nexport const simulateUnmount = () => unmountCallback()\n')
    .concat(`export {
      runs, events, tools, subagents, recoveries, commands, checkpoints, tree, recap, trajectory,
      activeSubagents, subagentSpawnPaused, recoverableRuns, recoverableLoading, recoverableError,
      checkpointPreview, previewOpen, selectedSessionId, selectedRunId, loading, loadError, runControlLoading, rollingBack,
      successMessages, errorMessages, loadSessionDetail, loadRunDetail, loadActiveSubagents,
      loadRecoverableRuns, openRecoverableRun, handleCheckpointPreview, handleRollback, handleRunControl, handleSubagentInterrupt,
    }\n`)
  writeFileSync(modulePath, `import { computed, ref } from 'vue'\n${transformed}`)

  const pending = new Map<string, PendingRequest[]>()
  const requestKey = (kind: RequestKind, id: string) => `${kind}:${id}`
  globalThis.__RUNS_VIEW_MOCKS__ = {
    request: (kind, id) => new Promise((resolve, reject) => {
      const key = requestKey(kind, id)
      pending.set(key, [...(pending.get(key) || []), { resolve, reject }])
    }),
  }

  function take(kind: RequestKind, id: string): PendingRequest {
    const key = requestKey(kind, id)
    const queue = pending.get(key) || []
    const request = queue.shift()
    pending.set(key, queue)
    assert.ok(request, `expected pending ${key} request`)
    return request
  }

  const view = await import(pathToFileURL(modulePath).href)
  view.runs.value = [{ run_id: 'old-run' }]
  view.events.value = [{ event_id: 'old-event' }]
  view.tree.value = { id: 'old-tree' }
  view.selectedSessionId.value = 'session-a'
  const loadA = view.loadSessionDetail()
  take('active', '').resolve({ subagents: [{ subagent_id: 'active-a' }], spawn_paused: false })
  assert.deepEqual(view.runs.value, [], 'new session request should clear stale runs immediately')
  assert.deepEqual(view.events.value, [], 'new session request should clear stale events immediately')
  assert.equal(view.tree.value, null, 'new session request should clear stale tree immediately')

  view.selectedSessionId.value = 'session-b'
  const loadB = view.loadSessionDetail()
  take('active', '').resolve({ subagents: [{ subagent_id: 'active-b' }], spawn_paused: false })
  take('runs', 'session-b').resolve([{ run_id: 'run-b' }])
  take('tree', 'session-b').resolve({ id: 'tree-b' })
  take('checkpoints', 'session-b').resolve([{ checkpoint_id: 'checkpoint-b' }])
  await flush()
  take('detail', 'run-b').resolve({
    events: [{ event_id: 'event-b' }],
    tools: [{ tool_call_id: 'tool-b' }],
    subagents: [{ subagent_id: 'subagent-b' }],
    recoveries: [{ recovery_id: 'recovery-b' }],
    commands: [{ command_id: 'command-b' }],
  })
  take('recap', 'session-b').resolve({ text: 'recap-b' })
  take('trajectory', 'session-b').resolve({ text: 'trajectory-b' })
  await loadB
  assert.equal(view.selectedRunId.value, 'run-b', 'latest session should select its own first run')
  assert.equal(view.events.value[0]?.event_id, 'event-b', 'latest session should commit its own detail')
  assert.equal(view.tree.value?.id, 'tree-b', 'latest session should commit its own tree')

  take('runs', 'session-a').resolve([{ run_id: 'run-a' }])
  take('tree', 'session-a').resolve({ id: 'tree-a' })
  take('checkpoints', 'session-a').resolve([{ checkpoint_id: 'checkpoint-a' }])
  await loadA
  assert.equal(view.selectedRunId.value, 'run-b', 'late old session must not replace the selected run')
  assert.equal(view.events.value[0]?.event_id, 'event-b', 'late old session must not replace run events')
  assert.equal(pending.get(requestKey('detail', 'run-a'))?.length || 0, 0, 'stale session must not start second-phase requests')

  view.selectedSessionId.value = 'session-d'
  const loadD = view.loadSessionDetail()
  take('active', '').resolve({ subagents: [], spawn_paused: false })
  take('runs', 'session-d').resolve([{ run_id: 'run-d' }])
  take('tree', 'session-d').resolve({ id: 'tree-d' })
  take('checkpoints', 'session-d').resolve([])
  await flush()
  const manualRun = view.loadRunDetail('run-manual')
  take('detail', 'run-manual').resolve({ events: [{ event_id: 'event-manual' }] })
  await manualRun
  take('detail', 'run-d').resolve({ events: [{ event_id: 'event-d' }] })
  take('recap', 'session-d').resolve({ text: 'recap-d' })
  take('trajectory', 'session-d').resolve({ text: 'trajectory-d' })
  await loadD
  assert.equal(view.selectedRunId.value, 'run-manual', 'newer run selection should invalidate an older session load')
  assert.equal(view.events.value[0]?.event_id, 'event-manual', 'older session detail must not replace a newer run selection')
  assert.equal(view.loading.value, false, 'manual run selection should stop the invalidated session spinner')

  const runOne = view.loadRunDetail('run-1')
  const runTwo = view.loadRunDetail('run-2')
  take('detail', 'run-2').resolve({ events: [{ event_id: 'event-2' }] })
  await runTwo
  take('detail', 'run-1').resolve({ events: [{ event_id: 'event-1' }] })
  await runOne
  assert.equal(view.selectedRunId.value, 'run-2', 'latest run selection should remain active')
  assert.equal(view.events.value[0]?.event_id, 'event-2', 'late old run detail must be discarded')

  view.runs.value = [
    { run_id: 'run-control', session_id: 'session-b' },
    { run_id: 'run-new', session_id: 'session-b' },
  ]
  view.selectedSessionId.value = 'session-b'
  view.selectedRunId.value = 'run-control'
  const pendingControl = view.handleRunControl('stop')
  const newRunSelection = view.loadRunDetail('run-new')
  take('detail', 'run-new').resolve({ events: [{ event_id: 'event-new' }] })
  await newRunSelection
  take('control', 'run-control:stop').resolve({})
  await pendingControl
  assert.equal(view.selectedRunId.value, 'run-new', 'old control completion must not replace a newer run selection')
  assert.equal(view.events.value[0]?.event_id, 'event-new', 'old control completion must not refresh over the newer detail')
  assert.equal(pending.get(requestKey('runs', 'session-b'))?.length || 0, 0, 'stale control must not reload the session')
  assert.equal(view.runControlLoading.value, '', 'completed stale control should clear only its own loading state')

  view.runs.value = [
    { run_id: 'run-rollback', session_id: 'session-b' },
    { run_id: 'run-after-rollback', session_id: 'session-b' },
  ]
  view.selectedRunId.value = 'run-rollback'
  const pendingRollback = view.handleRollback('checkpoint-control')
  const selectionAfterRollback = view.loadRunDetail('run-after-rollback')
  take('detail', 'run-after-rollback').resolve({ events: [{ event_id: 'event-after-rollback' }] })
  await selectionAfterRollback
  take('rollback', 'checkpoint-control').resolve({})
  await pendingRollback
  assert.equal(view.selectedRunId.value, 'run-after-rollback', 'old rollback completion must not replace a newer run selection')
  assert.equal(view.events.value[0]?.event_id, 'event-after-rollback', 'old rollback completion must not refresh over newer detail')
  assert.equal(view.rollingBack.value, '', 'completed stale rollback should clear only its own loading state')

  const recoverableRun = { run_id: 'run-recovery', session_id: 'session-r' }
  const openRecovery = view.openRecoverableRun(recoverableRun)
  take('active', '').resolve({ subagents: [], spawn_paused: false })
  take('runs', 'session-r').resolve([{ run_id: 'run-r-other', session_id: 'session-r' }])
  take('tree', 'session-r').resolve({ id: 'tree-r' })
  take('checkpoints', 'session-r').resolve([{ checkpoint_id: 'checkpoint-r' }])
  await flush()
  take('detail', 'run-recovery').resolve({
    run: { ...recoverableRun, status: 'success', recoverable: false },
    events: [{ event_id: 'event-recovery' }],
  })
  take('recap', 'session-r').resolve({ text: 'recap-r' })
  take('trajectory', 'session-r').resolve({ text: 'trajectory-r' })
  await openRecovery
  assert.equal(view.selectedSessionId.value, 'session-r', 'recoverable run should select its owning session')
  assert.equal(view.selectedRunId.value, 'run-recovery', 'recoverable run should remain the preferred run')
  assert.deepEqual(view.runs.value.map((run: any) => run.run_id), ['run-recovery', 'run-r-other'])
  assert.equal(view.runs.value[0]?.status, 'success', 'run list should use the latest detail snapshot')
  assert.equal(view.runs.value[0]?.recoverable, false, 'latest detail should remove stale recoverable state')
  assert.equal(view.tree.value?.id, 'tree-r', 'recoverable run should atomically load its session tree')
  assert.equal(view.recap.value?.text, 'recap-r', 'recoverable run should atomically load its session recap')
  assert.equal(view.events.value[0]?.event_id, 'event-recovery', 'recoverable run should load matching detail')

  const recoverableOld = view.loadRecoverableRuns()
  const recoverableNew = view.loadRecoverableRuns()
  const oldRecoverableRequest = take('recoverable', '')
  const newRecoverableRequest = take('recoverable', '')
  newRecoverableRequest.resolve([{ run_id: 'new-recoverable' }])
  await recoverableNew
  oldRecoverableRequest.resolve([{ run_id: 'old-recoverable' }])
  await recoverableOld
  assert.equal(view.recoverableRuns.value[0]?.run_id, 'new-recoverable', 'late recoverable list must be discarded')

  const oldPreview = view.handleCheckpointPreview('checkpoint-r')
  view.selectedSessionId.value = 'session-preview'
  const previewSession = view.loadSessionDetail()
  take('active', '').resolve({ subagents: [], spawn_paused: false })
  take('checkpoint', 'checkpoint-r').resolve({ text: 'old-preview' })
  await oldPreview
  assert.equal(view.previewOpen.value, false, 'session switch should keep an old checkpoint preview closed')
  assert.equal(view.checkpointPreview.value, null, 'session switch should clear old checkpoint preview data')
  take('runs', 'session-preview').resolve([])
  take('tree', 'session-preview').resolve({ id: 'tree-preview' })
  take('checkpoints', 'session-preview').resolve([])
  await flush()
  take('recap', 'session-preview').resolve(null)
  take('trajectory', 'session-preview').resolve(null)
  await previewSession

  const activeOld = view.loadActiveSubagents()
  const activeNew = view.loadActiveSubagents()
  const oldActiveRequest = take('active', '')
  const newActiveRequest = take('active', '')
  newActiveRequest.resolve({ subagents: [{ subagent_id: 'new-active' }], spawn_paused: true })
  await activeNew
  oldActiveRequest.resolve({ subagents: [{ subagent_id: 'old-active' }], spawn_paused: false })
  await activeOld
  assert.equal(view.activeSubagents.value[0]?.subagent_id, 'new-active', 'late active-subagent list must be discarded')
  assert.equal(view.subagentSpawnPaused.value, true, 'late active-subagent state must not replace the latest spawn state')

  view.selectedSessionId.value = 'session-c'
  const loadC = view.loadSessionDetail()
  take('active', '').resolve({ subagents: [], spawn_paused: false })
  take('runs', 'session-c').resolve([{ run_id: 'run-c' }])
  take('tree', 'session-c').reject(new Error('tree failed'))
  take('checkpoints', 'session-c').resolve([])
  await loadC
  assert.match(view.loadError.value || '', /tree failed/, 'current session failure should be visible')
  assert.deepEqual(view.runs.value, [], 'failed session should not retain a partial run list')
  assert.deepEqual(view.events.value, [], 'failed session should not retain old run detail')
  assert.equal(view.loading.value, false, 'current session failure should finish loading')

  const previousActiveSubagents = view.activeSubagents.value
  const previousRecoverableRuns = view.recoverableRuns.value
  const unmountedActive = view.loadActiveSubagents()
  const unmountedRecoverable = view.loadRecoverableRuns()
  const unmountedSubagentControl = view.handleSubagentInterrupt('subagent-before-unmount')
  view.simulateUnmount()
  take('active', '').resolve({ subagents: [{ subagent_id: 'late-active' }], spawn_paused: true })
  take('recoverable', '').resolve([{ run_id: 'late-recoverable' }])
  take('subcontrol', 'subagent-before-unmount:interrupt').resolve({})
  await Promise.all([unmountedActive, unmountedRecoverable, unmountedSubagentControl])
  assert.equal(view.activeSubagents.value, previousActiveSubagents, 'unmounted active response must not write state')
  assert.equal(view.recoverableRuns.value, previousRecoverableRuns, 'unmounted recoverable response must not write state')
  const selectedRunAfterUnmount = view.selectedRunId.value
  await Promise.all([
    view.loadRunDetail('after-unmount'),
    view.loadActiveSubagents(),
    view.loadRecoverableRuns(),
  ])
  assert.equal(view.selectedRunId.value, selectedRunAfterUnmount, 'unmounted run loader must not mutate selection')
  assert.equal(pending.get(requestKey('detail', 'after-unmount'))?.length || 0, 0, 'unmounted run loader must not issue requests')
  assert.equal(pending.get(requestKey('active', ''))?.length || 0, 0, 'unmounted active loader must not issue requests')
  assert.equal(pending.get(requestKey('recoverable', ''))?.length || 0, 0, 'unmounted recoverable loader must not issue requests')
} finally {
  console.error = originalConsoleError
  rmSync(temporaryDirectory, { recursive: true, force: true })
  delete globalThis.__RUNS_VIEW_MOCKS__
}
