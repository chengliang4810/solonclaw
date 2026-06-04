declare global {
  interface Window {
    __APP_SESSION_TOKEN__?: string
    __LOGIN_TOKEN__?: string
  }
}

const DEFAULT_BASE_URL = ''
const TOKEN_KEY = 'solonclaw_api_key'

export function getBaseUrlValue(): string {
  return localStorage.getItem('solonclaw_server_url') || DEFAULT_BASE_URL
}

export function getInjectedToken(): string {
  return window.__LOGIN_TOKEN__ || window.__APP_SESSION_TOKEN__ || ''
}

export function getApiKey(): string {
  return localStorage.getItem(TOKEN_KEY) || getInjectedToken()
}

export function setServerUrl(url: string) {
  localStorage.setItem('solonclaw_server_url', url)
}

export function setApiKey(key: string) {
  localStorage.setItem(TOKEN_KEY, key)
}

export function clearApiKey() {
  localStorage.removeItem(TOKEN_KEY)
}

export function hasApiKey(): boolean {
  return !!getApiKey()
}
