import { request } from '../client'

export interface GatewayStatus {
  profile: string
  port: number
  host: string
  url: string
  running: boolean
  pid?: number
}

interface DashboardStatusResponse {
  gateway_pid?: number | null
  gateway_platforms?: Record<string, {
    state: string
    connection_mode?: string | null
    detail?: string | null
  }>
}

function portFromUrl(value: string): number | null {
  try {
    const url = new URL(value)
    const port = Number(url.port)
    if (Number.isInteger(port) && port > 0) return port
    return url.protocol === 'https:' ? 443 : 80
  } catch {
    return null
  }
}

function currentGatewayPort(): number {
  const devServerUrl = typeof __SOLONCLAW_DEV_SERVER_URL__ === 'undefined' ? '' : __SOLONCLAW_DEV_SERVER_URL__
  const devPort = devServerUrl ? portFromUrl(devServerUrl) : null
  if (devPort) return devPort
  if (typeof window === 'undefined') return 80
  const port = Number(window.location.port)
  if (Number.isInteger(port) && port > 0) return port
  return window.location.protocol === 'https:' ? 443 : 80
}

export async function fetchGateways(): Promise<GatewayStatus[]> {
  const res = await request<DashboardStatusResponse>('/api/status')
  return Object.entries(res.gateway_platforms || {}).map(([name, value]) => ({
    profile: name,
    port: currentGatewayPort(),
    host: value.connection_mode || 'local',
    url: '',
    running: value.state === 'connected',
    pid: res.gateway_pid || undefined,
  }))
}

export async function checkGatewayHealth(name: string): Promise<GatewayStatus> {
  const gateways = await fetchGateways()
  const gateway = gateways.find((item) => item.profile === name)
  if (!gateway) throw new Error('Gateway not found')
  return gateway
}
