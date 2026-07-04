declare global {
  interface Window {
    __LOGIN_TOKEN__?: string
  }
}

const DEFAULT_BASE_URL = ''
const TOKEN_KEY = 'solonclaw_api_key'
const SERVER_URL_KEY = 'solonclaw_server_url'

function isLoopbackHost(hostname: string): boolean {
  const host = hostname.toLowerCase()
  return host === 'localhost' || host === '::1' || host === '[::1]' || host.startsWith('127.')
}

function isLoopbackUrl(value: string): boolean {
  try {
    return isLoopbackHost(new URL(value).hostname)
  } catch {
    return false
  }
}

export function getBaseUrlValue(): string {
  const stored = localStorage.getItem(SERVER_URL_KEY) || DEFAULT_BASE_URL
  if (stored && isLoopbackHost(window.location.hostname) && isLoopbackUrl(stored)) {
    return DEFAULT_BASE_URL
  }
  return stored
}

export function getInjectedToken(): string {
  return window.__LOGIN_TOKEN__ || ''
}

export function getApiKey(): string {
  return localStorage.getItem(TOKEN_KEY) || getInjectedToken()
}

export function setServerUrl(url: string) {
  localStorage.setItem(SERVER_URL_KEY, url)
}

export function setApiKey(key: string) {
  localStorage.setItem(TOKEN_KEY, key)
}

export function clearApiKey() {
  localStorage.removeItem(TOKEN_KEY)
  window.__LOGIN_TOKEN__ = ''
}

export function hasApiKey(): boolean {
  return !!getApiKey()
}
