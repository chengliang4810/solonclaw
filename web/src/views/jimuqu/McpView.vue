<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  NButton,
  NCheckbox,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NSelect,
  NSpin,
  NTag,
  useDialog,
  useMessage,
} from 'naive-ui'
import {
  beginMcpOAuth,
  checkMcpServer,
  clearMcpOAuth,
  completeMcpOAuth,
  connectMcpServer,
  deleteMcpServer,
  fetchMcpOAuthStatus,
  fetchMcpServers,
  handleMcpOAuth401,
  refreshMcpOAuth,
  refreshMcpTools,
  reloadMcpServer,
  saveMcpServer,
  type McpActionResult,
  type McpOAuthStatus,
  type McpServer,
} from '@/api/jimuqu/mcp'

const message = useMessage()
const dialog = useDialog()
const loading = ref(false)
const saving = ref(false)
const actionLoading = ref('')
const servers = ref<McpServer[]>([])
const enabled = ref(false)
const selectedId = ref('')
const showServerModal = ref(false)
const oauthStatus = ref<McpOAuthStatus | null>(null)
const oauthLoading = ref(false)
const lastAction = ref<McpActionResult | null>(null)
const oauthBeginUrl = ref('')

const transportOptions = [
  { label: 'stdio', value: 'stdio' },
  { label: 'streamable', value: 'streamable' },
  { label: 'streamable_stateless', value: 'streamable_stateless' },
  { label: 'sse', value: 'sse' },
]

const form = reactive({
  serverId: '',
  name: '',
  transport: 'stdio',
  endpoint: '',
  command: '',
  argsText: '',
  authText: '',
  oauthText: '',
  capabilitiesText: '',
  toolsText: '',
  enabled: true,
})

const oauthForm = reactive({
  authorization_endpoint: '',
  token_endpoint: '',
  client_id: '',
  client_secret: '',
  redirect_uri: '',
  scopes: '',
  code: '',
  state: '',
})

const selectedServer = computed(() => {
  return servers.value.find((server) => server.server_id === selectedId.value) || null
})

const tools = computed(() => {
  const raw = selectedServer.value?.tools
  return Array.isArray(raw) ? raw : []
})

const acpMethods = [
  'initialize',
  'authenticate',
  'session/new',
  'session/load',
  'session/resume',
  'session/list',
  'session/fork',
  'session/cancel',
  'session/set_model',
  'session/set_mode',
  'session/set_config_option',
  'session/prompt',
  'permissions/list_open',
  'permissions/respond',
]

onMounted(load)

async function load() {
  loading.value = true
  try {
    const data = await fetchMcpServers()
    enabled.value = data.enabled
    servers.value = data.servers || []
    if (!selectedId.value || !servers.value.some((server) => server.server_id === selectedId.value)) {
      selectedId.value = servers.value[0]?.server_id || ''
    }
    if (selectedId.value) {
      await loadOAuthStatus()
    } else {
      oauthStatus.value = null
    }
  } catch (err: any) {
    message.error(err.message || '加载 MCP 配置失败')
  } finally {
    loading.value = false
  }
}

function selectServer(server: McpServer) {
  selectedId.value = server.server_id
  lastAction.value = null
  oauthBeginUrl.value = ''
  loadOAuthStatus()
}

function openCreate() {
  resetForm()
  showServerModal.value = true
}

function openEdit(server: McpServer) {
  form.serverId = server.server_id
  form.name = server.name || ''
  form.transport = server.transport || 'stdio'
  form.endpoint = server.endpoint || ''
  form.command = server.command || ''
  form.argsText = prettyJson(server.args, true)
  form.authText = prettyJson(server.auth, true)
  form.oauthText = prettyJson(server.oauth, true)
  form.capabilitiesText = prettyJson(server.capabilities, true)
  form.toolsText = prettyJson(server.tools, true)
  form.enabled = !!server.enabled
  showServerModal.value = true
}

function resetForm() {
  form.serverId = ''
  form.name = ''
  form.transport = 'stdio'
  form.endpoint = ''
  form.command = ''
  form.argsText = ''
  form.authText = ''
  form.oauthText = ''
  form.capabilitiesText = ''
  form.toolsText = ''
  form.enabled = true
}

async function saveServer() {
  let payload: Record<string, unknown>
  try {
    payload = {
      serverId: form.serverId || undefined,
      name: form.name,
      transport: form.transport,
      endpoint: form.endpoint || undefined,
      command: form.command || undefined,
      args: parseJsonField(form.argsText, '启动参数'),
      auth: parseJsonField(form.authText, '鉴权配置'),
      oauth: parseJsonField(form.oauthText, 'OAuth 配置'),
      capabilities: parseJsonField(form.capabilitiesText, '能力配置'),
      tools: parseJsonField(form.toolsText, '工具快照'),
      enabled: form.enabled,
    }
  } catch (err: any) {
    message.error(err.message)
    return
  }
  saving.value = true
  try {
    const result: any = await saveMcpServer(payload)
    selectedId.value = result?.server_id || form.serverId
    showServerModal.value = false
    await load()
    message.success('MCP server 已保存')
  } catch (err: any) {
    message.error(err.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function parseJsonField(text: string, label: string) {
  const value = text.trim()
  if (!value) {
    return undefined
  }
  try {
    return JSON.parse(value)
  } catch {
    throw new Error(`${label} 不是有效 JSON`)
  }
}

function prettyJson(value: unknown, empty = false) {
  if (value === null || value === undefined || value === '') {
    return empty ? '' : '-'
  }
  if (typeof value === 'string') {
    return value
  }
  return JSON.stringify(value, null, 2)
}

function toolName(tool: unknown) {
  if (!tool || typeof tool !== 'object') {
    return String(tool || '')
  }
  const item = tool as Record<string, unknown>
  return String(item.prefixed_name || item.name || '未命名工具')
}

function toolDescription(tool: unknown) {
  if (!tool || typeof tool !== 'object') {
    return ''
  }
  const item = tool as Record<string, unknown>
  return String(item.description || item.title || '')
}

function statusType(status?: string) {
  if (status === 'ready' || status === 'connected' || status === 'authenticated') {
    return 'success'
  }
  if (status === 'error' || status === 'blocked' || status === 'expired') {
    return 'error'
  }
  if (status === 'pending' || status === 'configured') {
    return 'warning'
  }
  return 'default'
}

function formatTime(value?: number) {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString()
}

async function runAction(name: string, fn: () => Promise<McpActionResult>) {
  if (!selectedId.value) {
    return
  }
  actionLoading.value = name
  try {
    lastAction.value = await fn()
    await load()
    message.success('操作已完成')
  } catch (err: any) {
    message.error(err.message || '操作失败')
  } finally {
    actionLoading.value = ''
  }
}

async function loadOAuthStatus() {
  if (!selectedId.value) {
    oauthStatus.value = null
    return
  }
  oauthLoading.value = true
  try {
    oauthStatus.value = await fetchMcpOAuthStatus(selectedId.value)
    const oauth = oauthStatus.value.oauth || {}
    oauthForm.authorization_endpoint = String(oauth.authorization_endpoint || '')
    oauthForm.token_endpoint = String(oauth.token_endpoint || '')
    oauthForm.client_id = String(oauth.client_id || '')
    oauthForm.client_secret = ''
    oauthForm.redirect_uri = String(oauth.redirect_uri || '')
    oauthForm.scopes = Array.isArray(oauthStatus.value.scopes)
      ? oauthStatus.value.scopes.join(' ')
      : String(oauthStatus.value.scopes || oauth.scope || '')
    oauthForm.state = String(oauth.state || '')
  } catch (err: any) {
    message.error(err.message || '读取 OAuth 状态失败')
  } finally {
    oauthLoading.value = false
  }
}

async function startOAuth() {
  if (!selectedId.value) {
    return
  }
  actionLoading.value = 'oauth-begin'
  try {
    const result = await beginMcpOAuth(selectedId.value, {
      authorization_endpoint: oauthForm.authorization_endpoint,
      token_endpoint: oauthForm.token_endpoint,
      client_id: oauthForm.client_id,
      client_secret: oauthForm.client_secret || undefined,
      redirect_uri: oauthForm.redirect_uri || undefined,
      scopes: oauthForm.scopes,
    })
    oauthBeginUrl.value = result.authorization_url
    oauthForm.state = result.state
    await loadOAuthStatus()
    message.success('OAuth 授权链接已生成')
  } catch (err: any) {
    message.error(err.message || '启动 OAuth 失败')
  } finally {
    actionLoading.value = ''
  }
}

async function finishOAuth() {
  if (!selectedId.value) {
    return
  }
  actionLoading.value = 'oauth-complete'
  try {
    await completeMcpOAuth(selectedId.value, {
      code: oauthForm.code,
      state: oauthForm.state,
      token_endpoint: oauthForm.token_endpoint,
    })
    oauthForm.code = ''
    oauthBeginUrl.value = ''
    await loadOAuthStatus()
    message.success('OAuth 已完成')
  } catch (err: any) {
    message.error(err.message || '完成 OAuth 失败')
  } finally {
    actionLoading.value = ''
  }
}

async function runOAuthAction(name: string, fn: () => Promise<unknown>) {
  if (!selectedId.value) {
    return
  }
  actionLoading.value = name
  try {
    await fn()
    await loadOAuthStatus()
    await load()
    message.success('OAuth 操作已完成')
  } catch (err: any) {
    message.error(err.message || 'OAuth 操作失败')
  } finally {
    actionLoading.value = ''
  }
}

function confirmDelete(server: McpServer) {
  dialog.warning({
    title: '删除 MCP server',
    content: `确定删除 ${server.name || server.server_id} 吗？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteMcpServer(server.server_id)
      if (selectedId.value === server.server_id) {
        selectedId.value = ''
      }
      await load()
      message.success('已删除')
    },
  })
}

async function copy(text: string) {
  await navigator.clipboard.writeText(text)
  message.success('已复制')
}
</script>

<template>
  <div class="mcp-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">MCP / ACP</h2>
        <div class="header-subtitle">Server 注册、工具变更、OAuth 和本地编辑器适配器</div>
      </div>
      <div class="header-actions">
        <NTag :type="enabled ? 'success' : 'default'" :bordered="false">
          MCP {{ enabled ? '已启用' : '未启用' }}
        </NTag>
        <NButton size="small" :loading="loading" @click="load">刷新</NButton>
        <NButton size="small" type="primary" @click="openCreate">新增 Server</NButton>
      </div>
    </header>

    <NSpin :show="loading">
      <main class="mcp-layout">
        <section class="server-list">
          <div v-if="servers.length === 0" class="empty-state">暂无 MCP server</div>
          <button
            v-for="server in servers"
            :key="server.server_id"
            class="server-card"
            :class="{ active: selectedId === server.server_id }"
            @click="selectServer(server)"
          >
            <div class="server-card-head">
              <span class="server-name">{{ server.name || server.server_id }}</span>
              <NTag size="small" :type="statusType(server.status)" :bordered="false">{{ server.status || 'unknown' }}</NTag>
            </div>
            <div class="server-meta">
              <span>{{ server.transport }}</span>
              <span>{{ Array.isArray(server.tools) ? server.tools.length : 0 }} tools</span>
              <span>{{ server.enabled ? '启用' : '停用' }}</span>
            </div>
            <div v-if="server.last_error" class="server-error">{{ server.last_error }}</div>
          </button>
        </section>

        <section v-if="selectedServer" class="detail">
          <div class="detail-head">
            <div>
              <h3>{{ selectedServer.name }}</h3>
              <div class="detail-id">{{ selectedServer.server_id }}</div>
            </div>
            <div class="detail-actions">
              <NButton size="small" @click="openEdit(selectedServer)">编辑</NButton>
              <NButton size="small" :loading="actionLoading === 'check'" @click="runAction('check', () => checkMcpServer(selectedServer!.server_id))">检测</NButton>
              <NButton size="small" :loading="actionLoading === 'connect'" @click="runAction('connect', () => connectMcpServer(selectedServer!.server_id))">连接</NButton>
              <NButton size="small" :loading="actionLoading === 'reload'" @click="runAction('reload', () => reloadMcpServer(selectedServer!.server_id))">重载</NButton>
              <NButton size="small" :loading="actionLoading === 'tools'" @click="runAction('tools', () => refreshMcpTools(selectedServer!.server_id))">刷新工具</NButton>
              <NButton size="small" type="error" ghost @click="confirmDelete(selectedServer)">删除</NButton>
            </div>
          </div>

          <div class="summary-grid">
            <div class="summary-item">
              <span>Transport</span>
              <strong>{{ selectedServer.transport }}</strong>
            </div>
            <div class="summary-item">
              <span>工具数</span>
              <strong>{{ tools.length }}</strong>
            </div>
            <div class="summary-item">
              <span>上次检测</span>
              <strong>{{ formatTime(selectedServer.last_checked_at) }}</strong>
            </div>
            <div class="summary-item">
              <span>工具变更</span>
              <strong>{{ selectedServer.last_tools_changed_at ? formatTime(selectedServer.last_tools_changed_at) : '未记录' }}</strong>
            </div>
          </div>

          <div v-if="lastAction" class="action-result">
            <div class="result-title">
              最近操作：{{ lastAction.action || 'check' }}
              <NTag size="small" :type="lastAction.tool_changed_notification ? 'warning' : 'success'" :bordered="false">
                {{ lastAction.tool_changed_notification ? '工具有变更' : '工具未变更' }}
              </NTag>
            </div>
            <div class="result-meta">
              <span>schema sanitizer: {{ lastAction.schema_sanitizer || '-' }}</span>
              <span>hash: {{ lastAction.tools_hash || '-' }}</span>
              <span>工具数: {{ lastAction.previous_tool_count ?? '-' }} -> {{ lastAction.current_tool_count ?? lastAction.tool_count ?? '-' }}</span>
            </div>
            <div v-if="lastAction.added_tools?.length || lastAction.removed_tools?.length" class="tool-diff">
              <span v-if="lastAction.added_tools?.length">新增：{{ lastAction.added_tools.join(', ') }}</span>
              <span v-if="lastAction.removed_tools?.length">移除：{{ lastAction.removed_tools.join(', ') }}</span>
            </div>
            <div v-if="lastAction.error" class="server-error">{{ lastAction.error }}</div>
          </div>

          <div class="detail-grid">
            <section class="panel">
              <h4>连接目标</h4>
              <dl>
                <dt>Endpoint</dt>
                <dd>{{ selectedServer.endpoint || '-' }}</dd>
                <dt>Command</dt>
                <dd>{{ selectedServer.command || '-' }}</dd>
                <dt>Args</dt>
                <dd><pre>{{ prettyJson(selectedServer.args) }}</pre></dd>
              </dl>
            </section>

            <section class="panel">
              <h4>OAuth</h4>
              <NSpin :show="oauthLoading">
                <div class="oauth-status">
                  <NTag :type="statusType(oauthStatus?.status)" :bordered="false">{{ oauthStatus?.status || 'not_configured' }}</NTag>
                  <span>{{ oauthStatus?.authenticated ? '已认证' : '未认证' }}</span>
                  <span v-if="oauthStatus?.expires_at">到期：{{ formatTime(oauthStatus.expires_at) }}</span>
                </div>
                <div class="oauth-actions">
                  <NButton size="tiny" :loading="actionLoading === 'oauth-refresh'" @click="runOAuthAction('oauth-refresh', () => refreshMcpOAuth(selectedServer!.server_id))">刷新令牌</NButton>
                  <NButton size="tiny" :loading="actionLoading === 'oauth-401'" @click="runOAuthAction('oauth-401', () => handleMcpOAuth401(selectedServer!.server_id))">处理 401</NButton>
                  <NButton size="tiny" type="error" ghost :loading="actionLoading === 'oauth-clear'" @click="runOAuthAction('oauth-clear', () => clearMcpOAuth(selectedServer!.server_id))">清除</NButton>
                </div>
                <NForm label-placement="top" class="oauth-form">
                  <NFormItem label="Authorization endpoint">
                    <NInput v-model:value="oauthForm.authorization_endpoint" size="small" placeholder="https://provider.example/authorize" />
                  </NFormItem>
                  <NFormItem label="Token endpoint">
                    <NInput v-model:value="oauthForm.token_endpoint" size="small" placeholder="https://provider.example/token" />
                  </NFormItem>
                  <NFormItem label="Client ID">
                    <NInput v-model:value="oauthForm.client_id" size="small" />
                  </NFormItem>
                  <NFormItem label="Client secret">
                    <NInput v-model:value="oauthForm.client_secret" size="small" type="password" show-password-on="click" />
                  </NFormItem>
                  <NFormItem label="Redirect URI">
                    <NInput v-model:value="oauthForm.redirect_uri" size="small" />
                  </NFormItem>
                  <NFormItem label="Scopes">
                    <NInput v-model:value="oauthForm.scopes" size="small" placeholder="repo read:user" />
                  </NFormItem>
                  <div class="oauth-actions">
                    <NButton size="small" type="primary" :loading="actionLoading === 'oauth-begin'" @click="startOAuth">生成授权链接</NButton>
                    <NButton v-if="oauthBeginUrl" size="small" @click="copy(oauthBeginUrl)">复制授权链接</NButton>
                  </div>
                  <NFormItem label="Code">
                    <NInput v-model:value="oauthForm.code" size="small" />
                  </NFormItem>
                  <NFormItem label="State">
                    <NInput v-model:value="oauthForm.state" size="small" />
                  </NFormItem>
                  <NButton size="small" :loading="actionLoading === 'oauth-complete'" @click="finishOAuth">提交回调</NButton>
                </NForm>
              </NSpin>
            </section>

            <section class="panel tools-panel">
              <h4>工具快照</h4>
              <div v-if="tools.length === 0" class="empty-state compact">暂无工具</div>
              <div v-else class="tool-list">
                <div v-for="tool in tools" :key="toolName(tool)" class="tool-item">
                  <strong>{{ toolName(tool) }}</strong>
                  <span>{{ toolDescription(tool) || '无描述' }}</span>
                </div>
              </div>
            </section>

            <section class="panel">
              <h4>能力与安全</h4>
              <dl>
                <dt>Capabilities</dt>
                <dd><pre>{{ prettyJson(selectedServer.capabilities) }}</pre></dd>
                <dt>Last error</dt>
                <dd>{{ selectedServer.last_error || '-' }}</dd>
                <dt>Tools hash</dt>
                <dd>{{ selectedServer.last_tools_hash || '-' }}</dd>
              </dl>
            </section>
          </div>
        </section>

        <section v-else class="detail empty-detail">请选择或新增 MCP server</section>
      </main>
    </NSpin>

    <section class="acp-panel">
      <div class="acp-head">
        <div>
          <h3>ACP 本地适配器</h3>
          <p>通过 stdio 暴露会话、模型、权限和 MCP server 注入能力，供编辑器或本地宿主进程连接。</p>
        </div>
        <NButton size="small" @click="copy('java -jar jimuqu-agent.jar acp')">复制启动命令</NButton>
      </div>
      <div class="acp-command">java -jar jimuqu-agent.jar acp</div>
      <div class="acp-grid">
        <div class="acp-block">
          <h4>会话能力</h4>
          <p>新建、加载、恢复、列表、分支、取消会话，并支持会话级模型和模式配置。</p>
        </div>
        <div class="acp-block">
          <h4>MCP 注入</h4>
          <p>创建或恢复会话时可携带 mcp_servers，让编辑器侧 server 进入当前工具集。</p>
        </div>
        <div class="acp-block">
          <h4>权限流</h4>
          <p>支持列出待处理权限请求和提交允许/拒绝响应，复用项目内审批链路。</p>
        </div>
      </div>
      <div class="method-list">
        <NTag v-for="method in acpMethods" :key="method" size="small" :bordered="false">{{ method }}</NTag>
      </div>
    </section>

    <NModal v-model:show="showServerModal" preset="card" class="server-modal" :title="form.serverId ? '编辑 MCP server' : '新增 MCP server'">
      <NForm label-placement="top">
        <div class="form-grid">
          <NFormItem label="Server ID">
            <NInput v-model:value="form.serverId" :disabled="!!form.serverId" placeholder="留空自动生成" />
          </NFormItem>
          <NFormItem label="名称" required>
            <NInput v-model:value="form.name" placeholder="Local Docs" />
          </NFormItem>
          <NFormItem label="Transport">
            <NSelect v-model:value="form.transport" :options="transportOptions" />
          </NFormItem>
          <NFormItem label="启用">
            <NCheckbox v-model:checked="form.enabled">参与运行时工具发现</NCheckbox>
          </NFormItem>
        </div>
        <NFormItem v-if="form.transport !== 'stdio'" label="Endpoint">
          <NInput v-model:value="form.endpoint" placeholder="https://example.com/mcp" />
        </NFormItem>
        <NFormItem v-if="form.transport === 'stdio'" label="Command">
          <NInput v-model:value="form.command" placeholder="node" />
        </NFormItem>
        <NFormItem label="Args JSON">
          <NInput v-model:value="form.argsText" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" placeholder='["server.js", "--stdio"]' />
        </NFormItem>
        <div class="form-grid">
          <NFormItem label="Auth JSON">
            <NInput v-model:value="form.authText" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" />
          </NFormItem>
          <NFormItem label="OAuth JSON">
            <NInput v-model:value="form.oauthText" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" />
          </NFormItem>
        </div>
        <div class="form-grid">
          <NFormItem label="Capabilities JSON">
            <NInput v-model:value="form.capabilitiesText" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" />
          </NFormItem>
          <NFormItem label="Tools JSON">
            <NInput v-model:value="form.toolsText" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" placeholder='[{"name":"docs_search","description":"Search docs"}]' />
          </NFormItem>
        </div>
      </NForm>
      <template #footer>
        <div class="modal-actions">
          <NButton @click="showServerModal = false">取消</NButton>
          <NButton type="primary" :loading="saving" @click="saveServer">保存</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.mcp-view {
  height: calc(100 * var(--vh));
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.header-subtitle {
  margin-top: 4px;
  font-size: 12px;
  color: $text-muted;
}

.header-actions,
.detail-actions,
.oauth-actions,
.modal-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.mcp-layout {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
  padding: 20px;
  min-height: 0;
}

.server-list,
.detail,
.acp-panel {
  border: 1px solid $border-color;
  background: $bg-card;
  border-radius: $radius-sm;
}

.server-list {
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: calc(100 * var(--vh) - 260px);
  overflow-y: auto;
}

.server-card {
  width: 100%;
  border: 1px solid $border-color;
  background: $bg-primary;
  color: $text-primary;
  border-radius: $radius-sm;
  padding: 12px;
  text-align: left;
  cursor: pointer;

  &.active {
    border-color: $accent-primary;
    background: rgba(var(--accent-primary-rgb), 0.06);
  }
}

.server-card-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
}

.server-name {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.server-meta,
.result-meta,
.tool-diff {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 8px;
  color: $text-muted;
  font-size: 12px;
}

.server-error {
  margin-top: 8px;
  color: $error;
  font-size: 12px;
  word-break: break-word;
}

.detail {
  min-width: 0;
  padding: 16px;
  overflow: hidden;
}

.detail-head,
.acp-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

h3,
h4 {
  margin: 0;
}

.detail-id {
  margin-top: 4px;
  font-family: $font-code;
  font-size: 12px;
  color: $text-muted;
}

.summary-grid,
.detail-grid,
.acp-grid,
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.summary-grid {
  margin: 16px 0;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.summary-item {
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  padding: 10px;

  span {
    display: block;
    color: $text-muted;
    font-size: 12px;
    margin-bottom: 4px;
  }

  strong {
    font-size: 13px;
    font-weight: 600;
  }
}

.panel,
.action-result {
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  padding: 14px;
  background: $bg-primary;
  min-width: 0;
}

.action-result {
  margin-bottom: 16px;
}

.result-title,
.oauth-status {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

dl {
  margin: 12px 0 0;
}

dt {
  color: $text-muted;
  font-size: 12px;
  margin-top: 10px;
}

dd {
  margin: 4px 0 0;
  color: $text-secondary;
  word-break: break-word;
}

pre,
.acp-command {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: $font-code;
  font-size: 12px;
}

.oauth-form {
  margin-top: 12px;
}

.tool-list {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 320px;
  overflow-y: auto;
}

.tool-item {
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  padding: 10px;

  strong,
  span {
    display: block;
  }

  span {
    margin-top: 4px;
    color: $text-muted;
    font-size: 12px;
  }
}

.empty-state,
.empty-detail {
  padding: 32px;
  text-align: center;
  color: $text-muted;
}

.empty-state.compact {
  padding: 16px 0;
}

.acp-panel {
  margin: 0 20px 20px;
  padding: 16px;
  flex-shrink: 0;

  p {
    margin: 6px 0 0;
    color: $text-muted;
    font-size: 13px;
  }
}

.acp-command {
  margin-top: 12px;
  padding: 10px;
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  background: $code-bg;
}

.acp-grid {
  margin-top: 12px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.acp-block {
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  padding: 12px;
}

.method-list {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.server-modal {
  width: min(920px, calc(100vw - 32px));
}

.modal-actions {
  justify-content: flex-end;
}

@media (max-width: 1100px) {
  .mcp-layout,
  .summary-grid,
  .detail-grid,
  .acp-grid,
  .form-grid {
    grid-template-columns: 1fr;
  }

  .server-list {
    max-height: none;
  }
}
</style>
