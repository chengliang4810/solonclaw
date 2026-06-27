import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as configApi from '@/api/solonclaw/config'
import type { DisplayConfig, AgentConfig } from '@/api/solonclaw/config'

export const useSettingsStore = defineStore('settings', () => {
  const loading = ref(false)
  const saving = ref(false)

  const display = ref<DisplayConfig>({})
  const agent = ref<AgentConfig>({})
  const telegram = ref<Record<string, any>>({})
  const discord = ref<Record<string, any>>({})
  const slack = ref<Record<string, any>>({})
  const whatsapp = ref<Record<string, any>>({})
  const matrix = ref<Record<string, any>>({})
  const wecom = ref<Record<string, any>>({})
  const feishu = ref<Record<string, any>>({})
  const dingtalk = ref<Record<string, any>>({})
  const weixin = ref<Record<string, any>>({})
  const qqbot = ref<Record<string, any>>({})
  const yuanbao = ref<Record<string, any>>({})
  const platforms = ref<Record<string, any>>({})

  async function fetchSettings() {
    loading.value = true
    try {
      const data = await configApi.fetchConfig()
      display.value = data.display || {}
      agent.value = data.agent || {}
      telegram.value = data.telegram || {}
      discord.value = data.discord || {}
      slack.value = data.slack || {}
      whatsapp.value = data.whatsapp || {}
      matrix.value = data.matrix || {}
      wecom.value = data.wecom || {}
      feishu.value = data.feishu || {}
      dingtalk.value = data.dingtalk || {}
      weixin.value = data.weixin || {}
      qqbot.value = data.qqbot || {}
      yuanbao.value = data.yuanbao || {}
      platforms.value = data.platforms || {}
    } catch (err) {
      console.error('Failed to fetch settings:', err)
    } finally {
      loading.value = false
    }
  }

  async function saveSection(section: string, values: Record<string, any>) {
    saving.value = true
    try {
      await configApi.updateConfigSection(section, values)
    switch (section) {
      case 'display': display.value = { ...display.value, ...values }; break
      case 'agent': agent.value = { ...agent.value, ...values }; break
      case 'telegram': telegram.value = { ...telegram.value, ...values }; break
      case 'discord': discord.value = { ...discord.value, ...values }; break
      case 'slack': slack.value = { ...slack.value, ...values }; break
      case 'whatsapp': whatsapp.value = { ...whatsapp.value, ...values }; break
      case 'matrix': matrix.value = { ...matrix.value, ...values }; break
      case 'wechat': case 'wecom': wecom.value = { ...wecom.value, ...values }; break
      case 'feishu': feishu.value = { ...feishu.value, ...values }; break
      case 'dingtalk': dingtalk.value = { ...dingtalk.value, ...values }; break
      case 'weixin': weixin.value = { ...weixin.value, ...values }; break
      case 'qqbot': qqbot.value = { ...qqbot.value, ...values }; break
      case 'yuanbao': yuanbao.value = { ...yuanbao.value, ...values }; break
      case 'platforms': {
        // Deep-merge each platform's credentials
        for (const [key, val] of Object.entries(values)) {
          platforms.value = {
            ...platforms.value,
            [key]: { ...(platforms.value[key] || {}), ...(val as Record<string, any>) },
          }
        }
        break
      }
    }
    } finally {
      saving.value = false
    }
  }

  return {
    loading, saving,
    display, agent,
    telegram, discord, slack, whatsapp, matrix, wecom, feishu, dingtalk, weixin, qqbot, yuanbao, platforms,
    fetchSettings, saveSection,
  }
})
