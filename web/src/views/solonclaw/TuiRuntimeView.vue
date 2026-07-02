<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import * as QRCode from 'qrcode'
import { Button, Input, Spin, Switch, Tag, message } from 'antdv-next'
import {
  fetchTuiRuntimeOverview,
  fetchTuiRuntimeChannelQr,
  saveTuiRuntimeChannelConfig,
  saveTuiRuntimeModelApiKey,
  startTuiRuntimeChannelQr,
  TuiRuntimeRpcError,
  type TuiChannelField,
  type TuiChannelStatus,
  type TuiModelProvider,
  type TuiRuntimeOverview,
} from '@/api/solonclaw/tuiRuntime'
import ChannelQrPanel from '@/components/solonclaw/settings/ChannelQrPanel.vue'
import { normalizeChannelQrStatus, type ChannelQrPlatform } from '@/shared/channelQr'
import {
  providerAuthColor,
  providerAuthLabel,
  requiredSummary,
  statusLabel,
  statusTone,
} from '@/shared/tuiRuntimeDisplay'

const text = {
  apiKey: 'API Key',
  authenticated: '已认证',
  channelConfigSaved: '渠道配置已保存',
  channelRuntime: '渠道运行时',
  configMtime: '配置更新时间',
  configSnapshot: '配置快照',
  configured: '已配置',
  current: '当前',
  currentModel: '当前模型',
  currentProvider: '当前 Provider',
  description: '查看并配置独立终端前端使用的模型、国内渠道和工作区配置快照。',
  disabled: '已停用',
  emptyState: '暂无终端运行时数据',
  enableChannel: '启用渠道',
  enabledChannels: '已启用渠道',
  enterApiKey: '输入新的 API Key',
  loadFailed: '加载终端运行时状态失败',
  loading: '加载中...',
  missingConfig: '缺少配置',
  model: '模型',
  modelKeySaved: '模型密钥已保存',
  modelRuntime: '模型运行时',
  models: '个模型',
  needsSetup: '需要配置',
  noData: '暂无数据',
  noWritableValue: '请至少填写一个配置值',
  provider: 'Provider',
  readOnly: '只读',
  ready: '已就绪',
  refresh: '刷新',
  requiredFields: '必填字段',
  saveChannel: '保存渠道配置',
  saveModelKey: '保存密钥',
  setupStatus: '初始化状态',
  title: '终端运行时',
  unauthenticated: '未认证',
  workspaceConfig: '工作区配置',
} as const

const overview = ref<TuiRuntimeOverview | null>(null)
const loading = ref(false)
const providerApiKeys = reactive<Record<string, string>>({})
const providerSaving = reactive<Record<string, boolean>>({})
const channelForms = reactive<Record<string, Record<string, string>>>({})
const channelEnabled = reactive<Record<string, boolean>>({})
const channelSaving = reactive<Record<string, boolean>>({})

type UiQrStatus = 'idle' | 'loading' | 'waiting' | 'scaned' | 'confirmed' | 'error' | 'expired'

interface QrState {
  url: string
  imageUrl: string
  message: string
  id: string
  status: UiQrStatus
  failures: number
  timer: ReturnType<typeof setTimeout> | null
  domain: string
}

const qrStates = reactive<Record<string, QrState>>({})

const providers = computed(() => overview.value?.models.providers ?? [])
const channels = computed(() => overview.value?.channels.channels ?? [])
const currentProvider = computed(() => providers.value.find((provider) => provider.is_current))
const configuredChannels = computed(() => channels.value.filter((channel) => channel.configured).length)
const enabledChannels = computed(() => channels.value.filter((channel) => channel.enabled).length)

onMounted(() => {
  loadOverview()
})

async function loadOverview(): Promise<void> {
  loading.value = true
  try {
    const nextOverview = await fetchTuiRuntimeOverview()
    overview.value = nextOverview
    syncChannelForms(nextOverview.channels.channels ?? [])
  } catch (error) {
    if (error instanceof TuiRuntimeRpcError || error instanceof Error) {
      message.error(error.message)
    } else {
      message.error(text.loadFailed)
    }
  } finally {
    loading.value = false
  }
}

function timestampText(value: number | undefined): string {
  if (!value) return text.noData
  return new Date(value).toLocaleString()
}

function jsonText(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2)
}

function providerKey(provider: TuiModelProvider): string {
  return String(provider.slug || provider.name || '')
}

function providerApiKeyValue(provider: TuiModelProvider): string {
  return providerApiKeys[providerKey(provider)] ?? ''
}

function setProviderApiKey(provider: TuiModelProvider, value: string): void {
  providerApiKeys[providerKey(provider)] = value
}

function providerIsSaving(provider: TuiModelProvider): boolean {
  return !!providerSaving[providerKey(provider)]
}

async function saveModelApiKey(provider: TuiModelProvider): Promise<void> {
  const key = providerKey(provider)
  const apiKey = (providerApiKeys[key] ?? '').trim()
  if (!key || !apiKey) {
    message.warning(text.noWritableValue)
    return
  }
  providerSaving[key] = true
  try {
    const result = await saveTuiRuntimeModelApiKey(key, apiKey)
    if (result.ok === false) throw new Error(String(result.error || result.detail || text.loadFailed))
    providerApiKeys[key] = ''
    message.success(text.modelKeySaved)
    await loadOverview()
  } catch (error) {
    message.error(error instanceof Error ? error.message : text.loadFailed)
  } finally {
    providerSaving[key] = false
  }
}

function channelKey(channel: TuiChannelStatus): string {
  return String(channel.key || channel.channel || '')
}

function channelFields(channel: TuiChannelStatus): readonly TuiChannelField[] {
  if (channel.fields?.length) return channel.fields
  return (channel.required_keys ?? []).map(key => ({ key, label: key, required: true }))
}

function syncChannelForms(nextChannels: readonly TuiChannelStatus[]): void {
  for (const channel of nextChannels) {
    const key = channelKey(channel)
    if (!key) continue
    const form = channelForms[key] ?? {}
    for (const field of channelFields(channel)) {
      const fieldKey = String(field.key || '')
      if (fieldKey && form[fieldKey] === undefined) form[fieldKey] = ''
    }
    channelForms[key] = form
    channelEnabled[key] = !!channel.enabled
  }
}

function channelEnabledValue(channel: TuiChannelStatus): boolean {
  return !!channelEnabled[channelKey(channel)]
}

function setChannelEnabledValue(channel: TuiChannelStatus, value: boolean): void {
  const key = channelKey(channel)
  if (!key) return
  channelEnabled[key] = value
}

function channelFieldValue(channel: TuiChannelStatus, field: TuiChannelField): string {
  return channelForms[channelKey(channel)]?.[String(field.key || '')] ?? ''
}

function setChannelFieldValue(channel: TuiChannelStatus, field: TuiChannelField, value: string): void {
  const key = channelKey(channel)
  const fieldKey = String(field.key || '')
  if (!key || !fieldKey) return
  channelForms[key] = channelForms[key] ?? {}
  channelForms[key][fieldKey] = value
}

function channelIsSaving(channel: TuiChannelStatus): boolean {
  return !!channelSaving[channelKey(channel)]
}

async function saveChannelConfig(channel: TuiChannelStatus): Promise<void> {
  const key = channelKey(channel)
  const form = channelForms[key] ?? {}
  const values: Record<string, string | boolean> = Object.fromEntries(
    Object.entries(form)
      .map(([name, value]) => [name, value.trim()] as const)
      .filter(([, value]) => value.length > 0),
  )
  values.enabled = channelEnabledValue(channel)
  if (!key || Object.keys(values).length === 0) {
    message.warning(text.noWritableValue)
    return
  }
  channelSaving[key] = true
  try {
    const result = await saveTuiRuntimeChannelConfig(key, values)
    if (result.ok === false) throw new Error(String(result.error || result.detail || text.loadFailed))
    Object.keys(form).forEach(name => { form[name] = '' })
    message.success(text.channelConfigSaved)
    await loadOverview()
  } catch (error) {
    message.error(error instanceof Error ? error.message : text.loadFailed)
  } finally {
    channelSaving[key] = false
  }
}

function isQrPlatform(value: string): value is ChannelQrPlatform {
  return value === 'weixin' || value === 'feishu' || value === 'dingtalk'
}

function qrStateFor(channel: TuiChannelStatus): QrState {
  const key = channelKey(channel)
  if (!qrStates[key]) {
    qrStates[key] = {
      url: '',
      imageUrl: '',
      message: '',
      id: '',
      status: 'idle',
      failures: 0,
      timer: null,
      domain: '',
    }
  }
  return qrStates[key]
}

async function updateQrSource(state: QrState, raw: string): Promise<void> {
  const value = raw.trim()
  if (!value || value === state.url) return
  state.url = value
  state.imageUrl = /^data:image\//i.test(value)
    ? value
    : await QRCode.toDataURL(value, { width: 240, margin: 2, errorCorrectionLevel: 'M' })
}

function stopQrPoll(channel: string): void {
  const state = qrStates[channel]
  if (state?.timer) {
    clearTimeout(state.timer)
    state.timer = null
  }
}

async function applyQrPayload(channel: string, state: QrState, payload: Record<string, unknown>): Promise<void> {
  const view = normalizeChannelQrStatus(payload)
  state.id = view.qrcode || state.id
  state.message = view.error_message || view.message || ''
  state.domain = view.domain || ''
  await updateQrSource(state, view.qrcode_url || '')
  if (view.status === 'confirmed') {
    state.status = 'confirmed'
    await loadOverview()
    return
  }
  if (view.status === 'expired') {
    state.status = 'expired'
    return
  }
  if (view.status === 'error') {
    state.status = 'error'
    return
  }
  state.status = view.status === 'scaned' ? 'scaned' : (state.url ? 'waiting' : 'loading')
  pollChannelQr(channel)
}

async function startChannelQr(channel: TuiChannelStatus): Promise<void> {
  const key = channelKey(channel)
  if (!isQrPlatform(key)) return
  const state = qrStateFor(channel)
  stopQrPoll(key)
  Object.assign(state, { url: '', imageUrl: '', message: '', id: '', status: 'loading', failures: 0, domain: '' })
  try {
    await applyQrPayload(key, state, await startTuiRuntimeChannelQr(key))
  } catch (error) {
    state.status = 'error'
    state.message = error instanceof Error ? error.message : text.loadFailed
    message.error(state.message)
  }
}

function pollChannelQr(channel: string): void {
  const state = qrStates[channel]
  if (!state?.id) return
  state.timer = setTimeout(async () => {
    try {
      await applyQrPayload(channel, state, await fetchTuiRuntimeChannelQr(channel, state.id))
      state.failures = 0
    } catch (error) {
      state.failures += 1
      if (state.failures >= 3) {
        state.status = 'error'
        state.message = error instanceof Error ? error.message : text.loadFailed
        return
      }
      pollChannelQr(channel)
    }
  }, 3000)
}

onUnmounted(() => {
  Object.keys(qrStates).forEach(stopQrPoll)
})
</script>

<template>
  <div class="tui-runtime-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ text.title }}</h2>
        <p class="header-subtitle">{{ text.description }}</p>
      </div>
      <Button size="small" :loading="loading" @click="loadOverview">
        {{ text.refresh }}
      </Button>
    </header>

    <div class="tui-runtime-content">
      <Spin :spinning="loading" size="large" :description="text.loading">
        <div v-if="overview" class="runtime-grid">
          <section class="summary-card">
            <div class="card-heading">
              <span>{{ text.setupStatus }}</span>
              <Tag :color="overview.setup.provider_configured ? 'success' : 'warning'" size="small">
                {{ overview.setup.provider_configured ? text.ready : text.needsSetup }}
              </Tag>
            </div>
            <div class="metric-grid">
              <div class="metric-item">
                <span>{{ text.provider }}</span>
                <strong>{{ overview.setup.provider || text.noData }}</strong>
              </div>
              <div class="metric-item">
                <span>{{ text.model }}</span>
                <strong>{{ overview.setup.model || text.noData }}</strong>
              </div>
              <div class="metric-item">
                <span>{{ text.apiKey }}</span>
                <strong>{{ overview.setup.api_key || text.noData }}</strong>
              </div>
              <div class="metric-item">
                <span>{{ text.workspaceConfig }}</span>
                <strong class="mono-value">{{ overview.setup.workspace_config || text.noData }}</strong>
              </div>
            </div>
          </section>

          <section class="summary-card">
            <div class="card-heading">
              <span>{{ text.modelRuntime }}</span>
              <Tag :color="providerAuthColor(currentProvider?.authenticated)" size="small">
                {{ providerAuthLabel(currentProvider?.authenticated, text) }}
              </Tag>
            </div>
            <div class="model-current">
              <div>
                <span>{{ text.currentProvider }}</span>
                <strong>{{ overview.models.provider || text.noData }}</strong>
              </div>
              <div>
                <span>{{ text.currentModel }}</span>
                <strong>{{ overview.models.model || text.noData }}</strong>
              </div>
            </div>
            <div class="provider-list">
              <div v-for="provider in providers" :key="provider.slug || provider.name" class="provider-row">
                <div>
                  <strong>{{ provider.name || provider.slug || text.noData }}</strong>
                  <span>{{ provider.dialect || text.noData }}</span>
                </div>
                <div class="provider-tags">
                  <Tag v-if="provider.is_current" color="success" size="small">{{ text.current }}</Tag>
                  <Tag :color="providerAuthColor(provider.authenticated)" size="small">
                    {{ providerAuthLabel(provider.authenticated, text) }}
                  </Tag>
                  <span class="model-count">{{ provider.total_models ?? 0 }} {{ text.models }}</span>
                </div>
                <div class="provider-actions">
                  <Input
                    :value="providerApiKeyValue(provider)"
                    type="password"
                    size="small"
                    :placeholder="text.enterApiKey"
                    @update:value="value => setProviderApiKey(provider, String(value))"
                  />
                  <Button size="small" :loading="providerIsSaving(provider)" @click="saveModelApiKey(provider)">
                    {{ text.saveModelKey }}
                  </Button>
                </div>
              </div>
            </div>
          </section>

          <section class="summary-card channels-card">
            <div class="card-heading">
              <span>{{ text.channelRuntime }}</span>
              <Tag size="small">{{ configuredChannels }}/{{ channels.length }} {{ text.configured }}</Tag>
            </div>
            <div class="channel-meta">
              <span>{{ text.enabledChannels }}：{{ enabledChannels }}</span>
              <span>{{ text.configMtime }}: {{ timestampText(overview.channels.mtime) }}</span>
            </div>
            <div class="channel-list">
              <div v-for="channel in channels" :key="channel.key || channel.channel" class="channel-row">
                <div class="channel-row-main">
                  <div>
                    <strong>{{ channel.label || channel.key || text.noData }}</strong>
                    <span>{{ text.requiredFields }} {{ requiredSummary(channel) }}</span>
                  </div>
                  <div class="channel-status-actions">
                    <Tag :color="statusTone(channel.status)" size="small">
                      {{ statusLabel(channel.status, text) }}
                    </Tag>
                    <label class="channel-enabled-switch">
                      <span>{{ text.enableChannel }}</span>
                      <Switch
                        size="small"
                        :value="channelEnabledValue(channel)"
                        @update:value="value => setChannelEnabledValue(channel, !!value)"
                      />
                    </label>
                  </div>
                </div>
                <div v-if="channelFields(channel).length" class="channel-field-grid">
                  <label v-for="field in channelFields(channel)" :key="field.key" class="channel-field">
                    <span>{{ field.label || field.key }}</span>
                    <Input
                      :value="channelFieldValue(channel, field)"
                      :type="field.secret ? 'password' : 'text'"
                      size="small"
                      :placeholder="field.description || String(field.key || '')"
                      @update:value="value => setChannelFieldValue(channel, field, String(value))"
                    />
                  </label>
                  <Button size="small" :loading="channelIsSaving(channel)" @click="saveChannelConfig(channel)">
                    {{ text.saveChannel }}
                  </Button>
                </div>
                <ChannelQrPanel
                  v-if="channel.qr_supported && isQrPlatform(channelKey(channel))"
                  :state="qrStateFor(channel)"
                  :domain="qrStateFor(channel).domain"
                  @start="startChannelQr(channel)"
                />
              </div>
            </div>
          </section>

          <section class="summary-card config-card">
            <div class="card-heading">
              <span>{{ text.configSnapshot }}</span>
              <Tag size="small">{{ text.readOnly }}</Tag>
            </div>
            <pre>{{ jsonText(overview.config.config) }}</pre>
          </section>
        </div>
        <div v-else class="empty-state">{{ text.emptyState }}</div>
      </Spin>
    </div>
  </div>
</template>

<style scoped lang="scss" src="../../styles/tuiRuntimeView.scss"></style>
