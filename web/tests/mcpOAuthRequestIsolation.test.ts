import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

type RequestKind = 'status' | 'begin' | 'complete' | 'refresh' | 'handle401' | 'clear' | 'list'

interface PendingRequest {
  payload?: Record<string, unknown>
  resolve: (value: any) => void
  reject: (error: Error) => void
}

interface McpOAuthMocks {
  request: (kind: RequestKind, serverId: string, payload?: Record<string, unknown>) => Promise<any>
  successes: string[]
  errors: string[]
}

declare global {
  var __MCP_OAUTH_MOCKS__: McpOAuthMocks
}

/** 等待异步 continuation 创建下一批请求。 */
async function flush(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
}

const source = readFileSync(new URL('../src/views/solonclaw/McpView.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)?.[1]
assert.ok(script, 'McpView should contain a TypeScript setup script')

const testsDirectory = dirname(fileURLToPath(import.meta.url))
const temporaryDirectory = mkdtempSync(join(testsDirectory, '.tmp-mcp-oauth-'))
const modulePath = join(temporaryDirectory, 'mcp-oauth-test-module.ts')

try {
  const transformed = script
    .replace(/import[\s\S]*?from ['"][^'"]+['"]\n/g, '')
    .replace("const { t } = useI18n()", "const t = (key: string) => key")
    .replace(
      'const loading = ref(false)',
      `const onMounted = () => {}
let unmountCallback = () => {}
const onUnmounted = (callback: () => void) => { unmountCallback = callback }
const mcpTransportOptions = () => []
const asArray = <T>(value: unknown): T[] => Array.isArray(value) ? value as T[] : []
const hasItems = (value: unknown) => Array.isArray(value) && value.length > 0
const listCount = (value: unknown) => Array.isArray(value) ? value.length : 0
const trimText = (value: unknown) => String(value || '').trim()
const displayJson = (value: unknown) => JSON.stringify(value)
const mcpStatusTone = () => 'default'
const mcpTimestampText = (value: unknown) => String(value || '')
const copyToClipboard = async () => true
const fetchMcpOAuthStatus = (serverId: string) => globalThis.__MCP_OAUTH_MOCKS__.request('status', serverId)
const beginMcpOAuth = (serverId: string, payload: Record<string, unknown>) => globalThis.__MCP_OAUTH_MOCKS__.request('begin', serverId, payload)
const completeMcpOAuth = (serverId: string, payload: Record<string, unknown>) => globalThis.__MCP_OAUTH_MOCKS__.request('complete', serverId, payload)
const refreshMcpOAuth = (serverId: string) => globalThis.__MCP_OAUTH_MOCKS__.request('refresh', serverId)
const handleMcpOAuth401 = (serverId: string) => globalThis.__MCP_OAUTH_MOCKS__.request('handle401', serverId)
const clearMcpOAuth = (serverId: string) => globalThis.__MCP_OAUTH_MOCKS__.request('clear', serverId)
const fetchMcpServers = () => globalThis.__MCP_OAUTH_MOCKS__.request('list', '')
const saveMcpServer = async () => ({})
const deleteMcpServer = async () => {}
const checkMcpServer = async () => ({})
const connectMcpServer = async () => ({})
const refreshMcpTools = async () => ({})
const reloadMcpServer = async () => ({})
const reloadAllMcpServers = async () => ({})
const reloadAllMcpServersAsync = async () => ({})
const message = {
  success: (value: string) => globalThis.__MCP_OAUTH_MOCKS__.successes.push(value),
  error: (value: string) => globalThis.__MCP_OAUTH_MOCKS__.errors.push(value),
}
const Modal = { confirm: () => {} }
const loading = ref(false)`,
    )
    .concat('\nexport const simulateUnmount = () => unmountCallback()\n')
    .concat(`export {
      selectedId, servers, oauthStatus, oauthLoading, oauthBeginUrl, oauthForm, actionLoading,
      selectServer, loadOAuthStatus, startOAuth, finishOAuth, runOAuthAction,
      refreshMcpOAuth, handleMcpOAuth401, clearMcpOAuth,
    }\n`)
  writeFileSync(modulePath, `import { computed, reactive, ref } from 'vue'\n${transformed}`)

  const pending = new Map<string, PendingRequest[]>()
  const requestKey = (kind: RequestKind, serverId: string) => `${kind}:${serverId}`
  globalThis.__MCP_OAUTH_MOCKS__ = {
    request: (kind, serverId, payload) => new Promise((resolve, reject) => {
      const key = requestKey(kind, serverId)
      pending.set(key, [...(pending.get(key) || []), { payload, resolve, reject }])
    }),
    successes: [],
    errors: [],
  }

  /** 取出并移除指定的待决请求。 */
  function take(kind: RequestKind, serverId: string): PendingRequest {
    const key = requestKey(kind, serverId)
    const queue = pending.get(key) || []
    const request = queue.shift()
    pending.set(key, queue)
    assert.ok(request, `expected pending ${key} request`)
    return request
  }

  /** 构造包含可辨识 OAuth 配置的状态。 */
  function status(serverId: string) {
    return {
      status: `status-${serverId}`,
      authenticated: false,
      oauth: {
        authorization_endpoint: `https://${serverId}.example/authorize`,
        token_endpoint: `https://${serverId}.example/token`,
        client_id: `client-${serverId}`,
        state: `state-${serverId}`,
      },
      scopes: [`scope-${serverId}`],
    }
  }

  const view = await import(pathToFileURL(modulePath).href)

  view.selectServer({ server_id: 'A' })
  const statusA = take('status', 'A')
  view.selectServer({ server_id: 'B' })
  const statusB = take('status', 'B')
  statusB.resolve(status('B'))
  await flush()
  assert.equal(view.oauthStatus.value?.status, 'status-B', 'current Server status should populate the panel')
  assert.equal(view.oauthForm.client_id, 'client-B', 'current Server form should use its own OAuth data')
  statusA.resolve(status('A'))
  await flush()
  assert.equal(view.oauthStatus.value?.status, 'status-B', 'late old status must not replace the current Server')
  assert.equal(view.oauthForm.client_id, 'client-B', 'late old status must not replace the current form')
  assert.equal(view.oauthLoading.value, false, 'late old request must not change the current loading state')

  view.selectServer({ server_id: 'A' })
  take('status', 'A').resolve(status('A'))
  await flush()
  view.oauthForm.client_secret = 'secret-A'
  const beginA = view.startOAuth()
  const beginRequest = take('begin', 'A')
  assert.equal(beginRequest.payload?.client_id, 'client-A', 'begin request should capture the source Server form')
  assert.equal(beginRequest.payload?.client_secret, 'secret-A', 'begin request should capture the source secret')
  view.selectServer({ server_id: 'B' })
  take('status', 'B').resolve(status('B'))
  await flush()
  beginRequest.resolve({ authorization_url: 'https://A.example/login', state: 'begin-A' })
  await beginA
  assert.equal(view.oauthBeginUrl.value, '', 'late begin response must not expose an old Server link')
  assert.equal(view.oauthForm.state, 'state-B', 'late begin response must not replace the current state')
  assert.equal(globalThis.__MCP_OAUTH_MOCKS__.successes.length, 0, 'stale begin must not show a success message')

  view.selectServer({ server_id: 'A' })
  take('status', 'A').resolve(status('A'))
  await flush()
  view.oauthForm.code = 'code-A'
  const completeA = view.finishOAuth()
  const completeRequest = take('complete', 'A')
  assert.deepEqual(completeRequest.payload, {
    code: 'code-A',
    state: 'state-A',
    token_endpoint: 'https://A.example/token',
  }, 'callback request should capture the source Server form')
  view.selectServer({ server_id: 'B' })
  take('status', 'B').resolve(status('B'))
  await flush()
  completeRequest.resolve({})
  await completeA
  assert.equal(view.oauthForm.state, 'state-B', 'late callback must not alter the current Server form')
  assert.equal(globalThis.__MCP_OAUTH_MOCKS__.successes.length, 0, 'stale callback must not show a success message')

  const oauthActions = [
    ['oauth-refresh', 'refresh', view.refreshMcpOAuth],
    ['oauth-401', 'handle401', view.handleMcpOAuth401],
    ['oauth-clear', 'clear', view.clearMcpOAuth],
  ] as const
  for (const [name, kind, action] of oauthActions) {
    view.selectServer({ server_id: 'A' })
    take('status', 'A').resolve(status('A'))
    await flush()
    const running = view.runOAuthAction(name, 'A', action)
    const actionRequest = take(kind, 'A')
    view.selectServer({ server_id: 'B' })
    take('status', 'B').resolve(status('B'))
    await flush()
    actionRequest.resolve({})
    await running
    assert.equal(pending.get(requestKey('list', ''))?.length || 0, 0, `${kind} must not refresh after switching Server`)
    assert.equal(view.oauthStatus.value?.status, 'status-B', `${kind} must leave the current Server status intact`)
  }

  view.selectServer({ server_id: 'A' })
  take('status', 'A').resolve(status('A'))
  await flush()
  const successfulRefresh = view.runOAuthAction('oauth-refresh', 'A', view.refreshMcpOAuth)
  take('refresh', 'A').resolve({})
  await flush()
  take('list', '').resolve({ enabled: true, servers: [{ server_id: 'A', name: 'updated-A' }] })
  await flush()
  take('status', 'A').resolve({ ...status('A'), authenticated: true })
  await successfulRefresh
  assert.equal(view.servers.value[0]?.name, 'updated-A', 'current action should refresh the Server list')
  assert.equal(view.oauthStatus.value?.authenticated, true, 'current action should refresh OAuth status')
  assert.equal(globalThis.__MCP_OAUTH_MOCKS__.successes.at(-1), 'mcp.oauthActionCompleted')

  view.selectServer({ server_id: 'A' })
  const unmountedStatus = take('status', 'A')
  view.simulateUnmount()
  unmountedStatus.resolve(status('A'))
  await flush()
  assert.equal(view.oauthStatus.value, null, 'unmounted component must ignore late OAuth status')
  assert.equal(globalThis.__MCP_OAUTH_MOCKS__.errors.length, 0, 'stale and unmounted requests must not show errors')
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true })
  delete globalThis.__MCP_OAUTH_MOCKS__
}
