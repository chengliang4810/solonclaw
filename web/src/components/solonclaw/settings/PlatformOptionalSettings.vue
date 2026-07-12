<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import PlatformSwitchSettingRow from './PlatformSwitchSettingRow.vue'
import PlatformTextSettingRow from './PlatformTextSettingRow.vue'

type OptionalPlatform = 'wecom' | 'qqbot' | 'yuanbao'
type ChannelValues = Record<string, unknown>

interface PlatformCredentials {
  readonly enabled?: boolean
  readonly extra?: Record<string, unknown>
}

interface OptionalChannelState {
  readonly apiDomain?: string
  readonly botId?: string
  readonly websocketUrl?: string
}

interface OptionalSettingsStore {
  readonly wecom?: OptionalChannelState
  readonly qqbot: OptionalChannelState
  readonly yuanbao: OptionalChannelState
}

interface OptionalTextField {
  readonly field: string
  readonly source: 'credentials' | 'channel'
  readonly label?: string
  readonly labelKey?: string
  readonly hint?: string
  readonly hintKey?: string
  readonly placeholder: string
}

const props = defineProps<{
  platform: OptionalPlatform
  settingsStore: OptionalSettingsStore
  getCreds: (platform: string) => PlatformCredentials
  isSaving: (platform: string, field: string) => boolean
  saveCredentials: (platform: string, field: string, values: ChannelValues) => Promise<void> | void
  saveChannel: (platform: string, field: string, values: ChannelValues) => Promise<void> | void
}>()

const { t } = useI18n()

const textFieldConfigs: Record<OptionalPlatform, OptionalTextField[]> = {
  wecom: [
    { field: 'bot_id', source: 'credentials', labelKey: 'platform.botId', hintKey: 'platform.botIdHint', placeholder: '请输入机器人 ID' },
    { field: 'secret', source: 'credentials', labelKey: 'platform.appSecret', hintKey: 'platform.wecomSecretHint', placeholder: '请输入密钥' },
  ],
  qqbot: [
    { field: 'apiDomain', source: 'channel', label: 'API 域名', hint: 'QQBot REST API 地址，默认可保持官方域名', placeholder: '例如 https://api.sgroup.qq.com' },
    { field: 'websocketUrl', source: 'channel', label: 'WebSocket 地址', hint: '留空时自动从 gateway 接口获取', placeholder: '留空时自动获取' },
  ],
  yuanbao: [
    { field: 'app_id', source: 'credentials', label: '应用 ID', hint: '腾讯元宝应用 ID', placeholder: '请输入元宝 App ID' },
    { field: 'app_secret', source: 'credentials', label: '应用密钥', hint: '腾讯元宝应用密钥', placeholder: '请输入元宝 App Secret' },
    { field: 'botId', source: 'channel', label: '机器人 ID', hint: '腾讯元宝机器人 ID', placeholder: '请输入元宝 Bot ID' },
    { field: 'apiDomain', source: 'channel', label: 'API 域名', hint: '腾讯元宝 REST API 地址', placeholder: '例如 https://bot.yuanbao.tencent.com' },
    { field: 'websocketUrl', source: 'channel', label: 'WebSocket 地址', hint: '腾讯元宝网关连接地址', placeholder: '例如 wss://bot-wss.yuanbao.tencent.com/wss/connection' },
  ],
}

function fieldLabel(field: OptionalTextField) {
  return field.labelKey ? t(field.labelKey) : field.label || ''
}

function fieldHint(field: OptionalTextField) {
  return field.hintKey ? t(field.hintKey) : field.hint || ''
}

function fieldValue(field: OptionalTextField) {
  if (field.source === 'credentials') {
    return String(props.getCreds(props.platform).extra?.[field.field] || '')
  }
  return String(props.settingsStore[props.platform]?.[field.field as keyof OptionalChannelState] || '')
}

function saveTextField(field: OptionalTextField, value: string) {
  if (field.source === 'credentials') {
    return props.saveCredentials(props.platform, field.field, {
      extra: { ...props.getCreds(props.platform).extra, [field.field]: value },
    })
  }
  return props.saveChannel(props.platform, field.field, { [field.field]: value })
}
</script>

<template>
  <PlatformSwitchSettingRow :label="t('platform.channelEnabled')" :hint="t('platform.channelEnabledHint')" :value="Boolean(getCreds(platform).enabled)" :loading="isSaving(platform, 'enabled')" @change="v => saveCredentials(platform, 'enabled', { enabled: v })" />
  <PlatformTextSettingRow v-for="field in textFieldConfigs[platform]" :key="`${field.source}:${field.field}`" :label="fieldLabel(field)" :hint="fieldHint(field)" :value="fieldValue(field)" :loading="isSaving(platform, field.field)" :placeholder="field.placeholder" @change="v => saveTextField(field, v)" />
</template>
