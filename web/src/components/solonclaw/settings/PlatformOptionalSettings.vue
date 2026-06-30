<script setup lang="ts">
import { Input, Switch } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import SettingRow from './SettingRow.vue'

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
  readonly qqbot: OptionalChannelState
  readonly yuanbao: OptionalChannelState
}

defineProps<{
  platform: OptionalPlatform
  settingsStore: OptionalSettingsStore
  getCreds: (platform: string) => PlatformCredentials
  isSaving: (platform: string, field: string) => boolean
  saveCredentials: (platform: string, field: string, values: ChannelValues) => Promise<void> | void
  saveChannel: (platform: string, field: string, values: ChannelValues) => Promise<void> | void
}>()

const { t } = useI18n()
</script>

<template>
  <template v-if="platform === 'wecom'">
    <SettingRow :label="t('platform.channelEnabled')" :hint="t('platform.channelEnabledHint')">
      <Switch :value="getCreds('wecom').enabled" :loading="isSaving('wecom', 'enabled')" @update:value="v => saveCredentials('wecom', 'enabled', { enabled: v })" />
    </SettingRow>
    <SettingRow :label="t('platform.botId')" :hint="t('platform.botIdHint')">
      <Input :default-value="getCreds('wecom').extra?.bot_id || ''" :loading="isSaving('wecom', 'bot_id')" clearable size="small" class="input-lg" placeholder="请输入机器人 ID" @change="v => saveCredentials('wecom', 'bot_id', { extra: { ...getCreds('wecom').extra, bot_id: v } })" />
    </SettingRow>
    <SettingRow :label="t('platform.appSecret')" :hint="t('platform.wecomSecretHint')">
      <Input :default-value="getCreds('wecom').extra?.secret || ''" :loading="isSaving('wecom', 'secret')" clearable size="small" class="input-lg" placeholder="请输入密钥" @change="v => saveCredentials('wecom', 'secret', { extra: { ...getCreds('wecom').extra, secret: v } })" />
    </SettingRow>
  </template>

  <template v-if="platform === 'qqbot'">
    <SettingRow :label="t('platform.channelEnabled')" :hint="t('platform.channelEnabledHint')">
      <Switch :value="getCreds('qqbot').enabled" :loading="isSaving('qqbot', 'enabled')" @update:value="v => saveCredentials('qqbot', 'enabled', { enabled: v })" />
    </SettingRow>
    <SettingRow label="应用 ID" hint="QQBot 机器人应用 ID">
      <Input :default-value="getCreds('qqbot').extra?.app_id || ''" :loading="isSaving('qqbot', 'app_id')" clearable size="small" class="input-lg" placeholder="请输入 QQBot App ID" @change="v => saveCredentials('qqbot', 'app_id', { extra: { ...getCreds('qqbot').extra, app_id: v } })" />
    </SettingRow>
    <SettingRow label="客户端密钥" hint="QQBot 机器人 Client Secret">
      <Input :default-value="getCreds('qqbot').extra?.client_secret || ''" :loading="isSaving('qqbot', 'client_secret')" clearable size="small" class="input-lg" placeholder="请输入 QQBot Client Secret" @change="v => saveCredentials('qqbot', 'client_secret', { extra: { ...getCreds('qqbot').extra, client_secret: v } })" />
    </SettingRow>
    <SettingRow label="API 域名" hint="QQBot REST API 地址，默认可保持官方域名">
      <Input :default-value="settingsStore.qqbot.apiDomain || ''" :loading="isSaving('qqbot', 'apiDomain')" clearable size="small" class="input-lg" placeholder="例如 https://api.sgroup.qq.com" @change="v => saveChannel('qqbot', 'apiDomain', { apiDomain: v })" />
    </SettingRow>
    <SettingRow label="WebSocket 地址" hint="留空时自动从 gateway 接口获取">
      <Input :default-value="settingsStore.qqbot.websocketUrl || ''" :loading="isSaving('qqbot', 'websocketUrl')" clearable size="small" class="input-lg" placeholder="留空时自动获取" @change="v => saveChannel('qqbot', 'websocketUrl', { websocketUrl: v })" />
    </SettingRow>
  </template>

  <template v-if="platform === 'yuanbao'">
    <SettingRow :label="t('platform.channelEnabled')" :hint="t('platform.channelEnabledHint')">
      <Switch :value="getCreds('yuanbao').enabled" :loading="isSaving('yuanbao', 'enabled')" @update:value="v => saveCredentials('yuanbao', 'enabled', { enabled: v })" />
    </SettingRow>
    <SettingRow label="应用 ID" hint="腾讯元宝应用 ID">
      <Input :default-value="getCreds('yuanbao').extra?.app_id || ''" :loading="isSaving('yuanbao', 'app_id')" clearable size="small" class="input-lg" placeholder="请输入元宝 App ID" @change="v => saveCredentials('yuanbao', 'app_id', { extra: { ...getCreds('yuanbao').extra, app_id: v } })" />
    </SettingRow>
    <SettingRow label="应用密钥" hint="腾讯元宝应用密钥">
      <Input :default-value="getCreds('yuanbao').extra?.app_secret || ''" :loading="isSaving('yuanbao', 'app_secret')" clearable size="small" class="input-lg" placeholder="请输入元宝 App Secret" @change="v => saveCredentials('yuanbao', 'app_secret', { extra: { ...getCreds('yuanbao').extra, app_secret: v } })" />
    </SettingRow>
    <SettingRow label="机器人 ID" hint="腾讯元宝机器人 ID">
      <Input :default-value="settingsStore.yuanbao.botId || ''" :loading="isSaving('yuanbao', 'botId')" clearable size="small" class="input-lg" placeholder="请输入元宝 Bot ID" @change="v => saveChannel('yuanbao', 'botId', { botId: v })" />
    </SettingRow>
    <SettingRow label="API 域名" hint="腾讯元宝 REST API 地址">
      <Input :default-value="settingsStore.yuanbao.apiDomain || ''" :loading="isSaving('yuanbao', 'apiDomain')" clearable size="small" class="input-lg" placeholder="例如 https://bot.yuanbao.tencent.com" @change="v => saveChannel('yuanbao', 'apiDomain', { apiDomain: v })" />
    </SettingRow>
    <SettingRow label="WebSocket 地址" hint="腾讯元宝网关连接地址">
      <Input :default-value="settingsStore.yuanbao.websocketUrl || ''" :loading="isSaving('yuanbao', 'websocketUrl')" clearable size="small" class="input-lg" placeholder="例如 wss://bot-wss.yuanbao.tencent.com/wss/connection" @change="v => saveChannel('yuanbao', 'websocketUrl', { websocketUrl: v })" />
    </SettingRow>
  </template>
</template>
