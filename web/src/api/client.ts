import router from '@/router'
import { isDashboardOriginRejected } from './dashboardAuthError.ts'
export {
  clearApiKey,
  getApiKey,
  getBaseUrlValue,
  hasApiKey,
  setApiKey,
  setServerUrl,
} from './sessionAuth.ts'
import { clearApiKey, getApiKey, getBaseUrlValue } from './sessionAuth.ts'

export function redirectToLogin() {
  clearApiKey()
  if (router.currentRoute.value.name !== 'login') {
    router.replace({ name: 'login' })
  }
}

export function handleDashboardAuthFailure(status: number, body: string): boolean {
  if (status === 401 || isDashboardOriginRejected(status, body)) {
    redirectToLogin()
    return true
  }
  return false
}

export async function dashboardFetch(input: RequestInfo | URL, options: RequestInit = {}): Promise<Response> {
  const res = await fetch(input, options)
  if (res.ok) {
    return res
  }
  const text = await res.clone().text().catch(() => '')
  handleDashboardAuthFailure(res.status, text)
  return res
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const base = getBaseUrlValue()
  const url = `${base}${path}`
  const headers = new Headers(options.headers || {})

  if (!headers.has('Content-Type') && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }

  const apiKey = getApiKey()
  if (apiKey && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${apiKey}`)
  }

  const res = await dashboardFetch(url, { ...options, headers })

  if (res.status === 401) {
    throw new Error('Unauthorized')
  }

  if (!res.ok) {
    const text = await res.text().catch(() => '')
    if (handleDashboardAuthFailure(res.status, text)) {
      throw new Error('Unauthorized')
    }
    throw new Error(`API Error ${res.status}: ${text || res.statusText}`)
  }

  const contentType = res.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    const json = await res.json()
    if (json && typeof json === 'object' && 'success' in json && 'data' in json) {
      return json.data as T
    }
    return json
  }

  return (await res.text()) as T
}
