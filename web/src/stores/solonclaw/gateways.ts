import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchGateways, type GatewayStatus } from '@/api/solonclaw/gateways'

export const useGatewayStore = defineStore('gateways', () => {
  const gateways = ref<GatewayStatus[]>([])
  const loading = ref(false)
  const loadError = ref<string | null>(null)

  async function fetchStatus() {
    loading.value = true
    loadError.value = null
    try {
      const data = await fetchGateways()
      gateways.value = Array.isArray(data) ? data : Object.values(data || {})
    } catch (err) {
      console.error('Failed to fetch gateways:', err)
      loadError.value = err instanceof Error ? err.message : String(err || 'Failed to fetch gateways')
    } finally {
      loading.value = false
    }
  }

  return { gateways, loading, loadError, fetchStatus }
})
