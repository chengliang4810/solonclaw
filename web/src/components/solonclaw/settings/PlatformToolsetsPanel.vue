<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NSelect, NSwitch, useMessage } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import {
  fetchPlatformToolsets,
  fetchToolsets,
  updatePlatformToolsets,
  type PlatformToolsets,
  type ToolsetInfo,
} from '@/api/solonclaw/toolsets'

const { t } = useI18n()
const message = useMessage()
const toolsets = ref<ToolsetInfo[]>([])
const platforms = ref<Record<string, PlatformToolsets>>({})
const loading = ref(false)
const savingPlatform = ref('')
const platformNames = ['feishu', 'dingtalk', 'wecom', 'weixin', 'qqbot', 'yuanbao']
const platformLabels: Record<string, string> = {
  feishu: '飞书',
  dingtalk: '钉钉',
  wecom: '企业微信',
  weixin: '微信',
  qqbot: 'QQBot',
  yuanbao: '腾讯元宝',
}
const toolsetOptions = computed(() =>
  toolsets.value.map(item => ({
    label: item.label || item.name,
    value: item.name,
  })),
)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [items, platformConfig] = await Promise.all([
      fetchToolsets(),
      fetchPlatformToolsets(),
    ])
    toolsets.value = items
    platforms.value = platformConfig
  } finally {
    loading.value = false
  }
}

function configFor(platform: string): PlatformToolsets {
  return platforms.value[platform] || {
    platform,
    enabledToolsets: [],
    disabledToolsets: [],
    approvalRequired: false,
  }
}

async function save(platform: string, patch: Partial<PlatformToolsets>) {
  const current = configFor(platform)
  savingPlatform.value = platform
  try {
    const saved = await updatePlatformToolsets(platform, {
      enabledToolsets: patch.enabledToolsets ?? current.enabledToolsets,
      disabledToolsets: patch.disabledToolsets ?? current.disabledToolsets,
      approvalRequired: patch.approvalRequired ?? current.approvalRequired,
    })
    platforms.value = { ...platforms.value, [platform]: saved }
    message.success(t('settings.saved'))
  } catch (err: any) {
    message.error(err.message || t('settings.saveFailed'))
  } finally {
    savingPlatform.value = ''
  }
}
</script>

<template>
  <section class="toolsets-panel">
    <div class="toolsets-header">
      <div>
        <h3>{{ t('channels.toolsetsTitle') }}</h3>
        <p>{{ t('channels.toolsetsDescription') }}</p>
      </div>
      <NButton size="small" quaternary :loading="loading" @click="load">{{ t('common.refresh') }}</NButton>
    </div>
    <div class="platform-toolsets">
      <article v-for="platform in platformNames" :key="platform" class="platform-toolset-card">
        <div class="platform-toolset-title">
          <strong>{{ platformLabels[platform] }}</strong>
          <NSwitch
            size="small"
            :value="configFor(platform).approvalRequired"
            :loading="savingPlatform === platform"
            @update:value="value => save(platform, { approvalRequired: value })"
          />
        </div>
        <label>{{ t('channels.enabledToolsets') }}</label>
        <NSelect
          multiple
          clearable
          size="small"
          :options="toolsetOptions"
          :value="configFor(platform).enabledToolsets"
          :loading="loading || savingPlatform === platform"
          @update:value="value => save(platform, { enabledToolsets: value as string[] })"
        />
        <label>{{ t('channels.disabledToolsets') }}</label>
        <NSelect
          multiple
          clearable
          size="small"
          :options="toolsetOptions"
          :value="configFor(platform).disabledToolsets"
          :loading="loading || savingPlatform === platform"
          @update:value="value => save(platform, { disabledToolsets: value as string[] })"
        />
        <span class="approval-label">{{ t('channels.approvalRequired') }}</span>
      </article>
    </div>
  </section>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.toolsets-panel {
  background: $bg-card;
  border: 1px solid $border-color;
  border-radius: $radius-md;
  padding: 16px;
  margin-bottom: 16px;
}

.toolsets-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;

  h3 {
    font-size: 15px;
    font-weight: 600;
  }

  p {
    color: $text-muted;
    font-size: 12px;
  }
}

.platform-toolsets {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 320px), 1fr));
  gap: 12px;
}

.platform-toolset-card {
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  padding: 12px;

  label,
  .approval-label {
    display: block;
    color: $text-muted;
    font-size: 12px;
    margin: 10px 0 4px;
  }
}

.platform-toolset-title {
  display: flex;
  align-items: center;
  justify-content: space-between;

  strong {
    color: $text-primary;
    font-size: 13px;
    font-weight: 600;
  }
}
</style>
