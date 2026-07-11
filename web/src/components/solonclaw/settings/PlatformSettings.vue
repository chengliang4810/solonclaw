<script setup lang="ts">
import { computed, reactive } from 'vue'
import { message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/solonclaw/settings'
import {
  saveCredentials as saveCredsApi,
  fetchPlatformQrCode,
  pollPlatformQrStatus,
} from '@/api/solonclaw/config'
import type { ChannelQrPlatform } from '@/shared/channelQr'
import { useChannelQrPolling } from '@/shared/channelQrPolling'
import ChannelQrPanel from './ChannelQrPanel.vue'
import PlatformOptionalSettings from './PlatformOptionalSettings.vue'
import PlatformCard from './PlatformCard.vue'
import PlatformSwitchSettingRow from './PlatformSwitchSettingRow.vue'
import PlatformTextSettingRow from './PlatformTextSettingRow.vue'
import { normalizePlatformSettingsItems } from './platformDefinitions'

const settingsStore = useSettingsStore()
const { t } = useI18n()

// Track saving state per platform.field
const saving = reactive<Record<string, boolean>>({})

const platformSettingsItems = computed(() =>
  normalizePlatformSettingsItems(settingsStore.platformCatalog),
)

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

interface PrimaryTextSetting {
  readonly type: 'text'
  readonly field: string
  readonly source: 'credentials' | 'credentialRoot' | 'channelList'
  readonly label?: string
  readonly labelKey?: string
  readonly hint?: string
  readonly hintKey?: string
  readonly placeholder: string
}

interface PrimarySwitchSetting {
  readonly type: 'switch'
  readonly field: string
  readonly label?: string
  readonly labelKey: string
  readonly hint?: string
  readonly hintKey: string
}

type PrimarySetting = PrimaryTextSetting | PrimarySwitchSetting

const primarySettingConfigs: Record<ChannelQrPlatform, PrimarySetting[]> = {
  feishu: [
    { type: 'text', field: 'app_id', source: 'credentials', labelKey: 'platform.appId', hintKey: 'platform.appIdHint', placeholder: '请输入飞书应用 ID' },
    { type: 'text', field: 'app_secret', source: 'credentials', labelKey: 'platform.appSecret', hintKey: 'platform.appSecretHint', placeholder: '请输入应用密钥' },
    { type: 'switch', field: 'requireMention', labelKey: 'platform.requireMention', hintKey: 'platform.requireMentionGroup' },
    { type: 'text', field: 'freeResponseChats', source: 'channelList', labelKey: 'platform.freeResponseChats', hintKey: 'platform.freeResponseChatsHint', placeholder: 'chat_id1,chat_id2' },
  ],
  dingtalk: [
    { type: 'text', field: 'client_id', source: 'credentials', labelKey: 'platform.clientId', hintKey: 'platform.clientIdHint', placeholder: '请输入客户端 ID' },
    { type: 'text', field: 'client_secret', source: 'credentials', labelKey: 'platform.clientSecret', hintKey: 'platform.clientSecretHint', placeholder: '请输入客户端密钥' },
    { type: 'text', field: 'robot_code', source: 'credentials', label: '机器人编码', hint: '钉钉机器人编码', placeholder: '请输入机器人编码' },
    { type: 'switch', field: 'requireMention', labelKey: 'platform.requireMention', hintKey: 'platform.requireMentionGroup' },
    { type: 'text', field: 'freeResponseChats', source: 'channelList', labelKey: 'platform.freeResponseChats', hintKey: 'platform.freeResponseChatsHint', placeholder: 'chat_id1,chat_id2' },
  ],
  wecom: [],
  weixin: [
    { type: 'text', field: 'token', source: 'credentialRoot', labelKey: 'platform.weixinToken', hintKey: 'platform.weixinTokenHint', placeholder: '请输入令牌' },
    { type: 'text', field: 'account_id', source: 'credentials', labelKey: 'platform.accountId', hintKey: 'platform.accountIdHint', placeholder: '请输入账号 ID' },
  ],
}

function primarySettingLabel(field: PrimarySetting) {
  return field.labelKey ? t(field.labelKey) : field.label || ''
}

function primarySettingHint(field: PrimarySetting) {
  return field.hintKey ? t(field.hintKey) : field.hint || ''
}

function primaryChannelConfig(platform: ChannelQrPlatform) {
  return settingsStore[platform] as Record<string, any>
}

function primaryPlatform(platform: string): ChannelQrPlatform {
  if (isQrPanelPlatform(platform)) return platform
  throw new Error(`Unsupported QR platform: ${platform}`)
}

function primaryTextValue(platform: string, field: PrimaryTextSetting) {
  const channel = primaryPlatform(platform)
  if (field.source === 'credentials') {
    return String(getCreds(channel).extra?.[field.field] || '')
  }
  if (field.source === 'credentialRoot') {
    return String(getCreds(channel)[field.field] || '')
  }
  return channelListText(primaryChannelConfig(channel)[field.field])
}

function primarySwitchValue(platform: string, field: PrimarySwitchSetting) {
  return primaryChannelConfig(primaryPlatform(platform))[field.field] !== false
}

function savePrimaryText(platform: string, field: PrimaryTextSetting, value: string) {
  const channel = primaryPlatform(platform)
  if (field.source === 'credentials') {
    return saveCredentials(channel, field.field, {
      extra: { ...getCreds(channel).extra, [field.field]: value },
    })
  }
  if (field.source === 'credentialRoot') {
    return saveCredentials(channel, field.field, { [field.field]: value })
  }
  return saveChannel(channel, field.field, { [field.field]: splitChannelList(value) })
}

function savePrimarySwitch(platform: string, field: PrimarySwitchSetting, value: boolean) {
  return saveChannel(primaryPlatform(platform), field.field, { [field.field]: value })
}

const { states: qrStates, start: startQrLogin } = useChannelQrPolling<ChannelQrPlatform>(
  ['feishu', 'dingtalk', 'wecom', 'weixin'],
  {
    start: fetchPlatformQrCode,
    poll: pollPlatformQrStatus,
    startErrorMessage: () => t('platform.qrFetching'),
    pollErrorMessage: () => t('platform.qrFailed'),
    onError: (text) => message.error(text),
    onConfirmed: async () => {
      await settingsStore.fetchSettings()
      message.success(t('settings.saved'))
    },
  },
)

function isQrPanelPlatform(platform: string): platform is ChannelQrPlatform {
  return platform === 'feishu' || platform === 'dingtalk' || platform === 'wecom' || platform === 'weixin'
}

function qrPanelDomain(platform: string) {
  return platform === 'feishu' ? getCreds('feishu').domain : ''
}

function shouldShowQrEmptyStatus(platform: string) {
  return platform === 'weixin'
}

</script>

<template>
  <section class="settings-section">
    <PlatformCard
      v-for="p in platformSettingsItems"
      :key="p.key"
      :name="p.name"
      :icon="p.icon"
      :config="settingsStore[p.key as keyof typeof settingsStore] as Record<string, any>"
      :credentials="getCreds(p.key)"
    >
      <template v-if="isQrPanelPlatform(p.key)">
        <PlatformSwitchSettingRow :label="t('platform.channelEnabled')" :hint="t('platform.channelEnabledHint')" :value="Boolean(getCreds(p.key).enabled)" :loading="isSaving(p.key, 'enabled')" @change="v => saveCredentials(p.key, 'enabled', { enabled: v })" />
        <ChannelQrPanel
          :state="qrStates[p.key]"
          :domain="qrPanelDomain(p.key)"
          :show-empty-status="shouldShowQrEmptyStatus(p.key)"
          @start="startQrLogin(p.key)"
        />
        <template v-for="field in primarySettingConfigs[p.key]" :key="`${field.type}:${field.field}`">
          <PlatformTextSettingRow v-if="field.type === 'text'" :label="primarySettingLabel(field)" :hint="primarySettingHint(field)" :value="primaryTextValue(p.key, field)" :loading="isSaving(p.key, field.field)" :placeholder="field.placeholder" @change="v => savePrimaryText(p.key, field, v)" />
          <PlatformSwitchSettingRow v-else :label="primarySettingLabel(field)" :hint="primarySettingHint(field)" :value="primarySwitchValue(p.key, field)" :loading="isSaving(p.key, field.field)" @change="v => savePrimarySwitch(p.key, field, v)" />
        </template>
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
