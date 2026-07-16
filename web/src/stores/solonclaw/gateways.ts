import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchGateways, type GatewayStatus } from '@/api/solonclaw/gateways'
import { useProfileContextGuard } from '@/composables/useProfileContextGuard'

export const useGatewayStore = defineStore('gateways', () => {
  const gateways = ref<GatewayStatus[]>([])
  const loading = ref(false)
  const loadError = ref<string | null>(null)

  /** 清空当前 Profile 的网关状态。 */
  function resetProfileState(): void {
    gateways.value = []
    loading.value = false
    loadError.value = null
  }

  const profileContext = useProfileContextGuard(resetProfileState)

  async function fetchStatus() {
    const contextVersion = profileContext.capture()
    loading.value = true
    loadError.value = null
    try {
      const data = await fetchGateways()
      if (!profileContext.isCurrent(contextVersion)) return
      gateways.value = Array.isArray(data) ? data : Object.values(data || {})
    } catch (err) {
      if (profileContext.isCurrent(contextVersion)) {
        console.error('Failed to fetch gateways:', err)
        loadError.value = err instanceof Error ? err.message : String(err || 'Failed to fetch gateways')
      }
    } finally {
      if (profileContext.isCurrent(contextVersion)) loading.value = false
    }
  }

  return { gateways, loading, loadError, fetchStatus }
})
