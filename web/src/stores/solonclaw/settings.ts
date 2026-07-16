import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as configApi from '@/api/solonclaw/config'
import type { DisplayConfig, AgentConfig, GatewayConfig, PlatformCatalogItem } from '@/api/solonclaw/config'
import { useProfileContextGuard } from '@/composables/useProfileContextGuard'

export const useSettingsStore = defineStore('settings', () => {
  const loading = ref(false)
  const saving = ref(false)
  const loadError = ref<string | null>(null)

  const display = ref<DisplayConfig>({})
  const agent = ref<AgentConfig>({})
  const gateway = ref<GatewayConfig>({})
  const wecom = ref<Record<string, any>>({})
  const feishu = ref<Record<string, any>>({})
  const dingtalk = ref<Record<string, any>>({})
  const weixin = ref<Record<string, any>>({})
  const qqbot = ref<Record<string, any>>({})
  const yuanbao = ref<Record<string, any>>({})
  const platforms = ref<Record<string, any>>({})
  const platformCatalog = ref<readonly PlatformCatalogItem[]>([])

  /** 清空当前 Profile 的配置表单状态。 */
  function resetProfileState(): void {
    loading.value = false
    saving.value = false
    loadError.value = null
    display.value = {}
    agent.value = {}
    gateway.value = {}
    wecom.value = {}
    feishu.value = {}
    dingtalk.value = {}
    weixin.value = {}
    qqbot.value = {}
    yuanbao.value = {}
    platforms.value = {}
    platformCatalog.value = []
  }

  const profileContext = useProfileContextGuard(resetProfileState)

  async function fetchSettings() {
    const contextVersion = profileContext.capture()
    loading.value = true
    loadError.value = null
    try {
      const data = await configApi.fetchConfig()
      if (!profileContext.isCurrent(contextVersion)) return
      display.value = data.display || {}
      agent.value = data.agent || {}
      gateway.value = data.gateway || {}
      wecom.value = data.wecom || {}
      feishu.value = data.feishu || {}
      dingtalk.value = data.dingtalk || {}
      weixin.value = data.weixin || {}
      qqbot.value = data.qqbot || {}
      yuanbao.value = data.yuanbao || {}
      platforms.value = data.platforms || {}
      platformCatalog.value = data.platformCatalog || []
    } catch (err) {
      if (profileContext.isCurrent(contextVersion)) {
        console.error('Failed to fetch settings:', err)
        loadError.value = err instanceof Error ? err.message : String(err || 'Failed to fetch settings')
      }
    } finally {
      if (profileContext.isCurrent(contextVersion)) loading.value = false
    }
  }

  async function saveSection(section: string, values: Record<string, any>) {
    const contextVersion = profileContext.capture()
    saving.value = true
    try {
      await configApi.updateConfigSection(section, values)
      if (!profileContext.isCurrent(contextVersion)) return
      switch (section) {
        case 'display': display.value = { ...display.value, ...values }; break
        case 'agent': agent.value = { ...agent.value, ...values }; break
        case 'gateway': gateway.value = { ...gateway.value, ...values }; break
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
      if (profileContext.isCurrent(contextVersion)) saving.value = false
    }
  }

  return {
    loading, saving, loadError,
    display, agent, gateway,
    wecom, feishu, dingtalk, weixin, qqbot, yuanbao, platforms,
    platformCatalog,
    fetchSettings, saveSection,
  }
})
