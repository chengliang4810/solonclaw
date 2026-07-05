<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  Button,
  Checkbox,
  Form,
  FormItem,
  Input,
  InputPassword,
  Modal,
  Select,
  Spin,
  Tag,
  TextArea,
  message,
} from 'antdv-next'
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
  reloadAllMcpServers,
  reloadAllMcpServersAsync,
  reloadMcpServer,
  saveMcpServer,
  type McpActionResult,
  type McpOAuthStatus,
  type McpReloadAllResult,
  type McpServer,
} from '@/api/solonclaw/mcp'
import { mcpStatusTone, mcpTimestampText, mcpTransportOptions } from '@/shared/mcpDisplay'
import { asArray, displayJson, hasItems, listCount, trimText } from '@/shared/text'
import { copyToClipboard } from '@/utils/clipboard'

const { t } = useI18n()
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
const lastReloadAll = ref<McpReloadAllResult | null>(null)
const oauthBeginUrl = ref('')
const transportOptions = mcpTransportOptions()

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
  return asArray<unknown>(raw)
})

const lastAddedToolsText = computed(() => asArray<string>(lastAction.value?.added_tools).join(', '))
const lastRemovedToolsText = computed(() => asArray<string>(lastAction.value?.removed_tools).join(', '))
const hasLastToolDiff = computed(() => hasItems(lastAction.value?.added_tools) || hasItems(lastAction.value?.removed_tools))

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
    message.error(err.message || t('mcp.loadFailed'))
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
      args: parseJsonField(form.argsText, t('mcp.fields.commandArgs')),
      auth: parseJsonField(form.authText, t('mcp.fields.authConfigJson')),
      oauth: parseJsonField(form.oauthText, t('mcp.fields.oauthConfigJson')),
      capabilities: parseJsonField(form.capabilitiesText, t('mcp.fields.capabilitiesJson')),
      tools: parseJsonField(form.toolsText, t('mcp.fields.toolsSnapshot')),
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
    message.success(t('mcp.serverSaved'))
  } catch (err: any) {
    message.error(err.message || t('common.saveFailed'))
  } finally {
    saving.value = false
  }
}

function parseJsonField(text: string, label: string) {
  const value = trimText(text)
  if (!value) {
    return undefined
  }
  try {
    return JSON.parse(value)
  } catch {
    throw new Error(t('mcp.invalidJson', { label }))
  }
}

function prettyJson(value: unknown, empty = false) {
  return displayJson(value, { emptyText: empty ? '' : '-' })
}

function toolName(tool: unknown) {
  if (!tool || typeof tool !== 'object') {
    return String(tool || '')
  }
  const item = tool as Record<string, unknown>
  return String(item.prefixed_name || item.name || t('mcp.unnamedTool'))
}

function toolDescription(tool: unknown) {
  if (!tool || typeof tool !== 'object') {
    return ''
  }
  const item = tool as Record<string, unknown>
  return String(item.description || item.title || '')
}

async function runAction(name: string, fn: () => Promise<McpActionResult>) {
  if (!selectedId.value) {
    return
  }
  actionLoading.value = name
  try {
    lastAction.value = await fn()
    await load()
    message.success(t('mcp.actionCompleted'))
  } catch (err: any) {
    message.error(err.message || t('mcp.actionFailed'))
  } finally {
    actionLoading.value = ''
  }
}

async function reloadAllServers() {
  actionLoading.value = 'reload-all'
  try {
    lastReloadAll.value = await reloadAllMcpServers()
    await load()
    message.success(t('mcp.reloadAllCompleted'))
  } catch (err: any) {
    message.error(err.message || t('mcp.reloadAllFailed'))
  } finally {
    actionLoading.value = ''
  }
}

async function reloadAllServersAsync() {
  actionLoading.value = 'reload-all-async'
  try {
    lastReloadAll.value = await reloadAllMcpServersAsync()
    await load()
    message.success(t('mcp.reloadAllAsyncStarted'))
  } catch (err: any) {
    message.error(err.message || t('mcp.reloadAllFailed'))
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
    const scopes = oauthStatus.value.scopes
    oauthForm.scopes = Array.isArray(scopes)
      ? asArray<string>(scopes).join(' ')
      : String(scopes || oauth.scope || '')
    oauthForm.state = String(oauth.state || '')
  } catch (err: any) {
    message.error(err.message || t('mcp.oauthStatusLoadFailed'))
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
    message.success(t('mcp.oauthLinkGenerated'))
  } catch (err: any) {
    message.error(err.message || t('mcp.oauthStartFailed'))
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
    message.success(t('mcp.oauthCompleted'))
  } catch (err: any) {
    message.error(err.message || t('mcp.oauthCompleteFailed'))
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
    message.success(t('mcp.oauthActionCompleted'))
  } catch (err: any) {
    message.error(err.message || t('mcp.oauthActionFailed'))
  } finally {
    actionLoading.value = ''
  }
}

function confirmDelete(server: McpServer) {
  Modal.confirm({
    title: t('mcp.deleteTitle'),
    content: t('mcp.deleteConfirm', { name: server.name || server.server_id }),
    okText: t('common.delete'),
    cancelText: t('common.cancel'),
    onOk: async () => {
      await deleteMcpServer(server.server_id)
      if (selectedId.value === server.server_id) {
        selectedId.value = ''
      }
      await load()
      message.success(t('common.deleted'))
    },
  })
}

async function copy(text: string) {
  const ok = await copyToClipboard(text)
  if (ok) message.success(t('common.copied'))
  else message.error(t('common.copied') + ' ✗')
}
</script>

<template>
  <div class="mcp-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('mcp.title') }}</h2>
        <div class="header-subtitle">{{ t('mcp.description') }}</div>
      </div>
      <div class="header-actions">
        <Tag :color="enabled ? 'success' : 'default'" :bordered="false">
          {{ t(enabled ? 'mcp.enabled' : 'mcp.disabled') }}
        </Tag>
        <Button size="small" :loading="loading" @click="load">{{ t('mcp.refresh') }}</Button>
        <Button size="small" :loading="actionLoading === 'reload-all'" @click="reloadAllServers">{{ t('mcp.reloadAll') }}</Button>
        <Button size="small" :loading="actionLoading === 'reload-all-async'" @click="reloadAllServersAsync">{{ t('mcp.reloadAllAsync') }}</Button>
        <Button size="small" type="primary" @click="openCreate">{{ t('mcp.create') }}</Button>
      </div>
    </header>

    <section v-if="lastReloadAll" class="global-result">
      <div class="result-title">
        {{ t('mcp.reloadAllResultTitle') }}
        <Tag size="small" :color="lastReloadAll.tool_changed_notification ? 'warning' : 'success'" :bordered="false">
          {{ t(lastReloadAll.tool_changed_notification ? 'mcp.toolsChanged' : 'mcp.toolsUnchanged') }}
        </Tag>
      </div>
      <div class="result-meta">
        <span>{{ t('mcp.serverCount', { count: lastReloadAll.server_count }) }}</span>
        <span>{{ t('mcp.toolCount', { count: lastReloadAll.tool_count }) }}</span>
        <span>{{ t('mcp.changedCount', { count: lastReloadAll.changed_count }) }}</span>
        <span>{{ t('mcp.unchangedCount', { count: lastReloadAll.unchanged_count }) }}</span>
      </div>
      <div v-if="hasItems(lastReloadAll.changed_servers) || hasItems(lastReloadAll.unchanged_servers)" class="tool-diff">
        <span v-if="hasItems(lastReloadAll.changed_servers)">{{ t('mcp.changedServers', { servers: lastReloadAll.changed_servers.join(', ') }) }}</span>
        <span v-if="hasItems(lastReloadAll.unchanged_servers)">{{ t('mcp.unchangedServers', { servers: lastReloadAll.unchanged_servers.join(', ') }) }}</span>
      </div>
    </section>

    <Spin :spinning="loading">
      <main class="mcp-layout">
        <section class="server-list">
          <div v-if="servers.length === 0" class="empty-state">{{ t('mcp.emptyServers') }}</div>
          <button
            v-for="server in servers"
            :key="server.server_id"
            class="server-card"
            :class="{ active: selectedId === server.server_id }"
            @click="selectServer(server)"
          >
            <div class="server-card-head">
              <span class="server-name">{{ server.name || server.server_id }}</span>
              <Tag size="small" :color="mcpStatusTone(server.status)" :bordered="false">{{ server.status || t('common.unknown') }}</Tag>
            </div>
            <div class="server-meta">
              <span>{{ server.transport }}</span>
              <span>{{ t('mcp.toolCountBadge', { count: listCount(server.tools) }) }}</span>
              <span>{{ t(server.enabled ? 'common.enabled' : 'common.disabled') }}</span>
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
              <Button size="small" @click="openEdit(selectedServer)">{{ t('common.edit') }}</Button>
              <Button size="small" :loading="actionLoading === 'check'" @click="runAction('check', () => checkMcpServer(selectedServer!.server_id))">{{ t('mcp.actions.check') }}</Button>
              <Button size="small" :loading="actionLoading === 'connect'" @click="runAction('connect', () => connectMcpServer(selectedServer!.server_id))">{{ t('mcp.actions.connect') }}</Button>
              <Button size="small" :loading="actionLoading === 'reload'" @click="runAction('reload', () => reloadMcpServer(selectedServer!.server_id))">{{ t('mcp.actions.reload') }}</Button>
              <Button size="small" :loading="actionLoading === 'tools'" @click="runAction('tools', () => refreshMcpTools(selectedServer!.server_id))">{{ t('mcp.actions.refreshTools') }}</Button>
              <Button size="small" danger ghost @click="confirmDelete(selectedServer)">{{ t('common.delete') }}</Button>
            </div>
          </div>

          <div class="summary-grid">
            <div class="summary-item">
              <span>{{ t('mcp.fields.transport') }}</span>
              <strong>{{ selectedServer.transport }}</strong>
            </div>
            <div class="summary-item">
              <span>{{ t('mcp.fields.toolCount') }}</span>
              <strong>{{ tools.length }}</strong>
            </div>
            <div class="summary-item">
              <span>{{ t('mcp.fields.lastChecked') }}</span>
              <strong>{{ mcpTimestampText(selectedServer.last_checked_at) }}</strong>
            </div>
            <div class="summary-item">
              <span>{{ t('mcp.fields.toolsChangedAt') }}</span>
              <strong>{{ selectedServer.last_tools_changed_at ? mcpTimestampText(selectedServer.last_tools_changed_at) : t('common.notRecorded') }}</strong>
            </div>
          </div>

          <div v-if="lastAction" class="action-result">
            <div class="result-title">
              {{ t('mcp.recentAction', { action: lastAction.action || 'check' }) }}
              <Tag size="small" :color="lastAction.tool_changed_notification ? 'warning' : 'success'" :bordered="false">
                {{ t(lastAction.tool_changed_notification ? 'mcp.toolsChanged' : 'mcp.toolsUnchanged') }}
              </Tag>
            </div>
            <div class="result-meta">
              <span>{{ t('mcp.schemaSanitizer', { value: lastAction.schema_sanitizer || '-' }) }}</span>
              <span>{{ t('mcp.fields.toolsHash') }}：{{ lastAction.tools_hash || '-' }}</span>
              <span>{{ t('mcp.toolCount', { count: `${lastAction.previous_tool_count ?? '-'} -> ${lastAction.current_tool_count ?? lastAction.tool_count ?? '-'}` }) }}</span>
            </div>
            <div v-if="hasLastToolDiff" class="tool-diff">
              <span v-if="lastAddedToolsText">{{ t('mcp.addedTools', { tools: lastAddedToolsText }) }}</span>
              <span v-if="lastRemovedToolsText">{{ t('mcp.removedTools', { tools: lastRemovedToolsText }) }}</span>
            </div>
            <div v-if="lastAction.error" class="server-error">{{ lastAction.error }}</div>
          </div>

          <div class="detail-grid">
            <section class="panel">
              <h4>{{ t('mcp.connectionTarget') }}</h4>
              <dl>
                <dt>{{ t('mcp.fields.endpoint') }}</dt>
                <dd>{{ selectedServer.endpoint || '-' }}</dd>
                <dt>{{ t('mcp.fields.command') }}</dt>
                <dd>{{ selectedServer.command || '-' }}</dd>
                <dt>{{ t('mcp.fields.commandArgs') }}</dt>
                <dd><pre>{{ prettyJson(selectedServer.args) }}</pre></dd>
              </dl>
            </section>

            <section class="panel">
              <h4>{{ t('mcp.oauth.title') }}</h4>
              <Spin :spinning="oauthLoading">
                <div class="oauth-status">
                  <Tag :color="mcpStatusTone(oauthStatus?.status)" :bordered="false">{{ oauthStatus?.status || t('common.notConfigured') }}</Tag>
                  <span>{{ t(oauthStatus?.authenticated ? 'mcp.oauth.authenticated' : 'mcp.oauth.unauthenticated') }}</span>
                  <span v-if="oauthStatus?.expires_at">{{ t('mcp.oauth.expiresAt', { time: mcpTimestampText(oauthStatus.expires_at) }) }}</span>
                </div>
                <div class="oauth-actions">
                  <Button size="small" :loading="actionLoading === 'oauth-refresh'" @click="runOAuthAction('oauth-refresh', () => refreshMcpOAuth(selectedServer!.server_id))">{{ t('mcp.oauth.refreshToken') }}</Button>
                  <Button size="small" :loading="actionLoading === 'oauth-401'" @click="runOAuthAction('oauth-401', () => handleMcpOAuth401(selectedServer!.server_id))">{{ t('mcp.oauth.handle401') }}</Button>
                  <Button size="small" danger ghost :loading="actionLoading === 'oauth-clear'" @click="runOAuthAction('oauth-clear', () => clearMcpOAuth(selectedServer!.server_id))">{{ t('common.clear') }}</Button>
                </div>
                <Form layout="vertical" class="oauth-form">
                  <FormItem :label="t('mcp.oauth.authorizationEndpoint')">
                    <Input v-model:value="oauthForm.authorization_endpoint" size="small" :placeholder="t('mcp.oauth.authorizationEndpointPlaceholder')" />
                  </FormItem>
                  <FormItem :label="t('mcp.oauth.tokenEndpoint')">
                    <Input v-model:value="oauthForm.token_endpoint" size="small" :placeholder="t('mcp.oauth.tokenEndpointPlaceholder')" />
                  </FormItem>
                  <FormItem :label="t('mcp.oauth.clientId')">
                    <Input v-model:value="oauthForm.client_id" size="small" />
                  </FormItem>
                  <FormItem :label="t('mcp.oauth.clientSecret')">
                    <InputPassword v-model:value="oauthForm.client_secret" size="small" type="password" />
                  </FormItem>
                  <FormItem :label="t('mcp.oauth.redirectUri')">
                    <Input v-model:value="oauthForm.redirect_uri" size="small" />
                  </FormItem>
                  <FormItem :label="t('mcp.oauth.scopes')">
                    <Input v-model:value="oauthForm.scopes" size="small" :placeholder="t('mcp.oauth.scopesPlaceholder')" />
                  </FormItem>
                  <div class="oauth-actions">
                    <Button size="small" type="primary" :loading="actionLoading === 'oauth-begin'" @click="startOAuth">{{ t('mcp.oauth.generateLink') }}</Button>
                    <Button v-if="oauthBeginUrl" size="small" @click="copy(oauthBeginUrl)">{{ t('mcp.oauth.copyLink') }}</Button>
                  </div>
                  <FormItem :label="t('mcp.oauth.code')">
                    <Input v-model:value="oauthForm.code" size="small" />
                  </FormItem>
                  <FormItem :label="t('mcp.oauth.state')">
                    <Input v-model:value="oauthForm.state" size="small" />
                  </FormItem>
                  <Button size="small" :loading="actionLoading === 'oauth-complete'" @click="finishOAuth">{{ t('mcp.oauth.submitCallback') }}</Button>
                </Form>
              </Spin>
            </section>

            <section class="panel tools-panel">
              <h4>{{ t('mcp.toolsSnapshot') }}</h4>
              <div v-if="tools.length === 0" class="empty-state compact">{{ t('mcp.emptyTools') }}</div>
              <div v-else class="tool-list">
                <div v-for="tool in tools" :key="toolName(tool)" class="tool-item">
                  <strong>{{ toolName(tool) }}</strong>
                  <span>{{ toolDescription(tool) || t('mcp.noDescription') }}</span>
                </div>
              </div>
            </section>

            <section class="panel">
              <h4>{{ t('mcp.capabilitiesAndSecurity') }}</h4>
              <dl>
                <dt>{{ t('mcp.fields.capabilities') }}</dt>
                <dd><pre>{{ prettyJson(selectedServer.capabilities) }}</pre></dd>
                <dt>{{ t('mcp.fields.lastError') }}</dt>
                <dd>{{ selectedServer.last_error || '-' }}</dd>
                <dt>{{ t('mcp.fields.toolsHash') }}</dt>
                <dd>{{ selectedServer.last_tools_hash || '-' }}</dd>
              </dl>
            </section>
          </div>
        </section>

        <section v-else class="detail empty-detail">{{ t('mcp.emptyDetail') }}</section>
      </main>
    </Spin>

    <Modal v-model:open="showServerModal"  class="server-modal" :title="form.serverId ? t('mcp.editTitle') : t('mcp.createTitle')">
      <Form layout="vertical">
        <div class="form-grid">
          <FormItem :label="t('mcp.fields.serverId')">
            <Input v-model:value="form.serverId" :disabled="!!form.serverId" :placeholder="t('mcp.placeholders.autoServerId')" />
          </FormItem>
          <FormItem :label="t('mcp.fields.name')" required>
            <Input v-model:value="form.name" :placeholder="t('mcp.placeholders.name')" />
          </FormItem>
          <FormItem :label="t('mcp.fields.transport')">
            <Select v-model:value="form.transport" :options="transportOptions" />
          </FormItem>
          <FormItem :label="t('common.enable')">
            <Checkbox v-model:checked="form.enabled">{{ t('mcp.participateInRuntimeDiscovery') }}</Checkbox>
          </FormItem>
        </div>
        <FormItem v-if="form.transport !== 'stdio'" :label="t('mcp.fields.endpoint')">
          <Input v-model:value="form.endpoint" :placeholder="t('mcp.placeholders.endpoint')" />
        </FormItem>
        <FormItem v-if="form.transport === 'stdio'" :label="t('mcp.fields.command')">
          <Input v-model:value="form.command" :placeholder="t('mcp.placeholders.command')" />
        </FormItem>
        <FormItem :label="t('mcp.fields.commandArgsJson')">
          <TextArea v-model:value="form.argsText" :autosize="{ minRows: 2, maxRows: 5 }" :placeholder="t('mcp.placeholders.commandArgsJson')" />
        </FormItem>
        <div class="form-grid">
          <FormItem :label="t('mcp.fields.authConfigJson')">
            <TextArea v-model:value="form.authText" :autosize="{ minRows: 3, maxRows: 6 }" />
          </FormItem>
          <FormItem :label="t('mcp.fields.oauthConfigJson')">
            <TextArea v-model:value="form.oauthText" :autosize="{ minRows: 3, maxRows: 6 }" />
          </FormItem>
        </div>
        <div class="form-grid">
          <FormItem :label="t('mcp.fields.capabilitiesJson')">
            <TextArea v-model:value="form.capabilitiesText" :autosize="{ minRows: 3, maxRows: 6 }" />
          </FormItem>
          <FormItem :label="t('mcp.toolsSnapshot')">
            <TextArea v-model:value="form.toolsText" :autosize="{ minRows: 3, maxRows: 6 }" :placeholder="t('mcp.placeholders.toolsJson')" />
          </FormItem>
        </div>
      </Form>
      <template #footer>
        <div class="modal-actions">
          <Button @click="showServerModal = false">{{ t('common.cancel') }}</Button>
          <Button type="primary" :loading="saving" @click="saveServer">{{ t('common.save') }}</Button>
        </div>
      </template>
    </Modal>
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
.detail {
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

.detail-head {
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
.action-result,
.global-result {
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  padding: 14px;
  background: $bg-primary;
  min-width: 0;
}

.action-result {
  margin-bottom: 16px;
}

.global-result {
  margin: 0 20px;
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

pre {
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
  .form-grid {
    grid-template-columns: 1fr;
  }

  .server-list {
    max-height: none;
  }
}
</style>
