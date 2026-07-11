import router from '@/router'
import { appendManagementProfile } from '@/shared/profileScope'
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

let managementProfile = ''

export function setManagementProfile(profile: string): void {
  managementProfile = profile.trim()
}

export function getManagementProfile(): string {
  return managementProfile
}

export function profiledApiPath(path: string): string {
  return appendManagementProfile(path, managementProfile)
}

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

function apiErrorField(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

function apiErrorMessage(status: number, statusText: string, body: string): string {
  const text = body.trim()
  if (text) {
    try {
      const json = JSON.parse(text) as unknown
      if (json && typeof json === 'object') {
        const payload = json as Record<string, unknown>
        const message = apiErrorField(payload.message) || apiErrorField(payload.error) || apiErrorField(payload.detail)
        if (message) return message
        const code = apiErrorField(payload.code)
        if (code) return `API Error ${status}: ${code}`
      }
    } catch {
      // 非 JSON 响应继续按原始文本展示。
    }
  }
  return `API Error ${status}: ${text || statusText}`
}

export async function dashboardFetch(input: RequestInfo | URL, options: RequestInit = {}): Promise<Response> {
  const scopedInput = typeof input === 'string'
    ? profiledApiPath(input)
    : input instanceof URL
      ? new URL(profiledApiPath(input.toString()))
      : input
  const res = await fetch(scopedInput, options)
  if (res.ok) {
    return res
  }
  const text = await res.clone().text().catch(() => '')
  handleDashboardAuthFailure(res.status, text)
  return res
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const base = getBaseUrlValue()
  // `profile` 查询参数是 Dashboard 管理目标的统一契约；写请求可在后端兼容同名 JSON 字段，
  // 但客户端始终保留查询参数，避免修改各业务请求体结构。
  const url = `${base}${profiledApiPath(path)}`
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
    throw new Error(apiErrorMessage(res.status, res.statusText, text))
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
