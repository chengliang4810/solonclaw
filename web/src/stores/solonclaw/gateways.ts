import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchGateways, type GatewayStatus } from '@/api/solonclaw/gateways'

export const useGatewayStore = defineStore('gateways', () => {
  const gateways = ref<GatewayStatus[]>([])
  const loading = ref(false)

  async function fetchStatus() {
    loading.value = true
    try {
      const data = await fetchGateways()
      gateways.value = Array.isArray(data) ? data : Object.values(data || {})
    } finally {
      loading.value = false
    }
  }

  return { gateways, loading, fetchStatus }
})
