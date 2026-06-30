<script setup lang="ts">
import { reactive, onUnmounted } from 'vue'
import * as QRCode from 'qrcode'
import { Switch, Input, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/solonclaw/settings'
import {
  saveCredentials as saveCredsApi,
  fetchPlatformQrCode,
  pollPlatformQrStatus,
} from '@/api/solonclaw/config'
import type { ChannelQrPlatform } from '@/shared/channelQr'
import ChannelQrPanel from './ChannelQrPanel.vue'
import PlatformOptionalSettings from './PlatformOptionalSettings.vue'
import PlatformCard from './PlatformCard.vue'
import SettingRow from './SettingRow.vue'
import { PLATFORM_SETTINGS_ITEMS } from './platformDefinitions'

const settingsStore = useSettingsStore()
const { t } = useI18n()

// Track saving state per platform.field
const saving = reactive<Record<string, boolean>>({})

function savingKey(platform: string, field: string) {
  return `${platform}.${field}`
}

function isSaving(platform: string, field: string) {
  return !!saving[savingKey(platform, field)]
}

// Immediate save for switches
async function immediateSave(platform: string, field: string, saveFn: () => Promise<void>) {
  const key = savingKey(platform, field)
  saving[key] = true
  try {
    await saveFn()
    message.success(t('settings.saved'))
  } catch (err: any) {
    message.error(t('settings.saveFailed'))
  } finally {
    saving[key] = false
  }
}

async function saveChannel(platform: string, field: string, values: Record<string, any>) {
  immediateSave(platform, field, () => settingsStore.saveSection(platform, values))
}

// Save credentials to workspace/config.yml through the dashboard API
async function saveCredentials(platform: string, field: string, values: Record<string, any>) {
  immediateSave(platform, field, async () => {
    await saveCredsApi(platform, values)
    await settingsStore.fetchSettings()
  })
}

function getCreds(key: string) {
  return (settingsStore.platforms[key] || {}) as Record<string, any>
}

function channelListText(value: unknown) {
  if (Array.isArray(value)) return value.filter(item => !!item).join(',')
  return typeof value === 'string' ? value : ''
}

function splitChannelList(value: unknown) {
  return String(value || '')
    .split(',')
    .map(item => item.trim())
    .filter(item => item.length > 0)
}

type UiQrStatus = 'idle' | 'loading' | 'waiting' | 'scaned' | 'confirmed' | 'error' | 'expired'

interface QrState {
  url: string
  imageUrl: string
  message: string
  id: string
  status: UiQrStatus
  failures: number
  timer: ReturnType<typeof setTimeout> | null
}

const qrStates = reactive<Record<ChannelQrPlatform, QrState>>({
  feishu: { url: '', imageUrl: '', message: '', id: '', status: 'idle', failures: 0, timer: null },
  dingtalk: { url: '', imageUrl: '', message: '', id: '', status: 'idle', failures: 0, timer: null },
  weixin: { url: '', imageUrl: '', message: '', id: '', status: 'idle', failures: 0, timer: null },
})

async function updateQrSource(state: QrState, raw: string) {
  const value = (raw || '').trim()
  if (!value || value === state.url) return
  state.url = value
  state.imageUrl = /^data:image\//i.test(value)
    ? value
    : await QRCode.toDataURL(value, {
      width: 240,
      margin: 2,
      errorCorrectionLevel: 'M',
    })
}

async function startQrLogin(platform: ChannelQrPlatform) {
  const state = qrStates[platform]
  state.status = 'loading'
  state.url = ''
  state.imageUrl = ''
  state.message = ''
  state.id = ''
  state.failures = 0
  stopQrPoll(platform)

  try {
    const data = await fetchPlatformQrCode(platform)
    state.id = data.qrcode
    await updateQrSource(state, data.qrcode_url)
    state.status = state.url ? 'waiting' : 'loading'
    pollQrStatus(platform)
  } catch (err: any) {
    state.status = 'error'
    state.message = err.message || t('platform.qrFetching')
    message.error(err.message || t('platform.qrFetching'))
  }
}

function pollQrStatus(platform: ChannelQrPlatform) {
  const state = qrStates[platform]
  if (!state.id) return
  state.timer = setTimeout(async () => {
    try {
      const data = await pollPlatformQrStatus(platform, state.id)
      state.failures = 0
      state.message = data.error_message || data.message || ''
      await updateQrSource(state, data.qrcode_url || '')
      if (data.status === 'wait') {
        state.status = state.url ? 'waiting' : 'loading'
        pollQrStatus(platform)
      } else if (data.status === 'scaned') {
        state.status = 'scaned'
        pollQrStatus(platform)
      } else if (data.status === 'expired') {
        state.status = 'expired'
      } else if (data.status === 'error') {
        state.status = 'error'
      } else if (data.status === 'confirmed') {
        state.status = 'confirmed'
        await settingsStore.fetchSettings()
        message.success(t('settings.saved'))
      }
    } catch (err: any) {
      state.failures += 1
      if (state.failures >= 3) {
        state.status = 'error'
        state.message = err?.message || t('platform.qrFailed')
        return
      }
      pollQrStatus(platform)
    }
  }, 3000)
}

function stopQrPoll(platform: ChannelQrPlatform) {
  const state = qrStates[platform]
  if (state.timer) {
    clearTimeout(state.timer)
    state.timer = null
  }
}

onUnmounted(() => {
  stopQrPoll('feishu')
  stopQrPoll('dingtalk')
  stopQrPoll('weixin')
})

</script>

<template>
  <section class="settings-section">
    <PlatformCard
      v-for="p in PLATFORM_SETTINGS_ITEMS"
      :key="p.key"
      :name="p.name"
      :icon="p.icon"
      :config="settingsStore[p.key as keyof typeof settingsStore] as Record<string, any>"
      :credentials="getCreds(p.key)"
    >
      <template v-if="p.key === 'feishu'">
        <SettingRow :label="t('platform.channelEnabled')" :hint="t('platform.channelEnabledHint')">
          <Switch :value="getCreds('feishu').enabled" :loading="isSaving('feishu', 'enabled')" @update:value="v => saveCredentials('feishu', 'enabled', { enabled: v })" />
        </SettingRow>
        <ChannelQrPanel
          :state="qrStates.feishu"
          :domain="getCreds('feishu').domain"
          @start="startQrLogin('feishu')"
        />
        <SettingRow :label="t('platform.appId')" :hint="t('platform.appIdHint')">
          <Input :default-value="getCreds('feishu').extra?.app_id || ''" :loading="isSaving('feishu', 'app_id')" clearable size="small" class="input-lg" placeholder="请输入飞书应用 ID" @change="v => saveCredentials('feishu', 'app_id', { extra: { ...getCreds('feishu').extra, app_id: v } })" />
        </SettingRow>
        <SettingRow :label="t('platform.appSecret')" :hint="t('platform.appSecretHint')">
          <Input :default-value="getCreds('feishu').extra?.app_secret || ''" :loading="isSaving('feishu', 'app_secret')" clearable size="small" class="input-lg" placeholder="请输入应用密钥" @change="v => saveCredentials('feishu', 'app_secret', { extra: { ...getCreds('feishu').extra, app_secret: v } })" />
        </SettingRow>
        <SettingRow :label="t('platform.requireMention')" :hint="t('platform.requireMentionGroup')">
          <Switch :value="settingsStore.feishu.requireMention !== false" :loading="isSaving('feishu', 'requireMention')" @update:value="v => saveChannel('feishu', 'requireMention', { requireMention: v })" />
        </SettingRow>
        <SettingRow :label="t('platform.freeResponseChats')" :hint="t('platform.freeResponseChatsHint')">
          <Input :default-value="channelListText(settingsStore.feishu.freeResponseChats)" :loading="isSaving('feishu', 'freeResponseChats')" size="small" placeholder="chat_id1,chat_id2" @change="v => saveChannel('feishu', 'freeResponseChats', { freeResponseChats: splitChannelList(v) })" />
        </SettingRow>
      </template>

      <template v-if="p.key === 'dingtalk'">
        <SettingRow :label="t('platform.channelEnabled')" :hint="t('platform.channelEnabledHint')">
          <Switch :value="getCreds('dingtalk').enabled" :loading="isSaving('dingtalk', 'enabled')" @update:value="v => saveCredentials('dingtalk', 'enabled', { enabled: v })" />
        </SettingRow>
        <ChannelQrPanel
          :state="qrStates.dingtalk"
          @start="startQrLogin('dingtalk')"
        />
        <SettingRow :label="t('platform.clientId')" :hint="t('platform.clientIdHint')">
          <Input :default-value="getCreds('dingtalk').extra?.client_id || ''" :loading="isSaving('dingtalk', 'client_id')" clearable size="small" class="input-lg" placeholder="请输入客户端 ID" @change="v => saveCredentials('dingtalk', 'client_id', { extra: { ...getCreds('dingtalk').extra, client_id: v } })" />
        </SettingRow>
        <SettingRow :label="t('platform.clientSecret')" :hint="t('platform.clientSecretHint')">
          <Input :default-value="getCreds('dingtalk').extra?.client_secret || ''" :loading="isSaving('dingtalk', 'client_secret')" clearable size="small" class="input-lg" placeholder="请输入客户端密钥" @change="v => saveCredentials('dingtalk', 'client_secret', { extra: { ...getCreds('dingtalk').extra, client_secret: v } })" />
        </SettingRow>
        <SettingRow label="机器人编码" hint="钉钉机器人编码">
          <Input :default-value="getCreds('dingtalk').extra?.robot_code || ''" :loading="isSaving('dingtalk', 'robot_code')" clearable size="small" class="input-lg" placeholder="请输入机器人编码" @change="v => saveCredentials('dingtalk', 'robot_code', { extra: { ...getCreds('dingtalk').extra, robot_code: v } })" />
        </SettingRow>
        <SettingRow :label="t('platform.requireMention')" :hint="t('platform.requireMentionGroup')">
          <Switch :value="settingsStore.dingtalk.requireMention !== false" :loading="isSaving('dingtalk', 'requireMention')" @update:value="v => saveChannel('dingtalk', 'requireMention', { requireMention: v })" />
        </SettingRow>
        <SettingRow :label="t('platform.freeResponseChats')" :hint="t('platform.freeResponseChatsHint')">
          <Input :default-value="channelListText(settingsStore.dingtalk.freeResponseChats)" :loading="isSaving('dingtalk', 'freeResponseChats')" size="small" placeholder="chat_id1,chat_id2" @change="v => saveChannel('dingtalk', 'freeResponseChats', { freeResponseChats: splitChannelList(v) })" />
        </SettingRow>
      </template>

      <template v-if="p.key === 'weixin'">
        <SettingRow :label="t('platform.channelEnabled')" :hint="t('platform.channelEnabledHint')">
          <Switch :value="getCreds('weixin').enabled" :loading="isSaving('weixin', 'enabled')" @update:value="v => saveCredentials('weixin', 'enabled', { enabled: v })" />
        </SettingRow>
        <ChannelQrPanel
          :state="qrStates.weixin"
          show-empty-status
          @start="startQrLogin('weixin')"
        />
        <SettingRow :label="t('platform.weixinToken')" :hint="t('platform.weixinTokenHint')">
          <Input :default-value="getCreds('weixin').token || ''" :loading="isSaving('weixin', 'token')" clearable size="small" class="input-lg" placeholder="请输入令牌" @change="v => saveCredentials('weixin', 'token', { token: v })" />
        </SettingRow>
        <SettingRow :label="t('platform.accountId')" :hint="t('platform.accountIdHint')">
          <Input :default-value="getCreds('weixin').extra?.account_id || ''" :loading="isSaving('weixin', 'account_id')" clearable size="small" class="input-lg" placeholder="请输入账号 ID" @change="v => saveCredentials('weixin', 'account_id', { extra: { ...getCreds('weixin').extra, account_id: v } })" />
        </SettingRow>
      </template>

      <PlatformOptionalSettings
        v-if="p.key === 'wecom' || p.key === 'qqbot' || p.key === 'yuanbao'"
        :platform="p.key"
        :settings-store="settingsStore"
        :get-creds="getCreds"
        :is-saving="isSaving"
        :save-credentials="saveCredentials"
        :save-channel="saveChannel"
      />
    </PlatformCard>
  </section>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.settings-section {
  margin-top: 16px;
}
</style>
