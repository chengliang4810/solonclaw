<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Button, Spin, Tag, message } from 'antdv-next'
import {
  fetchTuiRuntimeOverview,
  TuiRuntimeRpcError,
  type TuiRuntimeOverview,
} from '@/api/solonclaw/tuiRuntime'
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
  channelRuntime: '渠道运行时',
  configMtime: '配置更新时间',
  configSnapshot: '配置快照',
  configured: '已配置',
  current: '当前',
  currentModel: '当前模型',
  currentProvider: '当前 Provider',
  description: '查看独立终端前端使用的模型、渠道和工作区配置快照。此页面只读展示 TUI JSON-RPC 控制面，不提供通用配置写入入口。',
  disabled: '已停用',
  emptyState: '暂无终端运行时数据',
  enabledChannels: '已启用渠道',
  loadFailed: '加载终端运行时状态失败',
  loading: '加载中...',
  missingConfig: '缺少配置',
  model: '模型',
  modelRuntime: '模型运行时',
  models: '个模型',
  needsSetup: '需要配置',
  noData: '暂无数据',
  provider: 'Provider',
  readOnly: '只读',
  ready: '已就绪',
  refresh: '刷新',
  requiredFields: '必填字段',
  setupStatus: '初始化状态',
  title: '终端运行时',
  unauthenticated: '未认证',
  workspaceConfig: '工作区配置',
} as const

const overview = ref<TuiRuntimeOverview | null>(null)
const loading = ref(false)

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
    overview.value = await fetchTuiRuntimeOverview()
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
                <div>
                  <strong>{{ channel.label || channel.key || text.noData }}</strong>
                  <span>{{ text.requiredFields }} {{ requiredSummary(channel) }}</span>
                </div>
                <Tag :color="statusTone(channel.status)" size="small">
                  {{ statusLabel(channel.status, text) }}
                </Tag>
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
