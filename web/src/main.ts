import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import { i18n } from './i18n'
import App from './App.vue'
import './styles/global.scss'

// Apply dark class before mount to prevent FOUC
const savedTheme = localStorage.getItem('solonclaw_theme') || 'system'
const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
if (savedTheme === 'dark' || (savedTheme === 'system' && prefersDark)) {
  document.documentElement.classList.add('dark')
}

// 兼容 hash 路由里的访问令牌：先读取，再规范化 URL，避免路由 query 参与初始化导致空白页。
const urlParams = new URLSearchParams(window.location.search)
const hash = window.location.hash
const hashQueryIndex = hash.indexOf('?')
const hashRoutePath = hashQueryIndex >= 0 ? hash.slice(0, hashQueryIndex) : hash
const hashQuery = hashQueryIndex >= 0 ? hash.slice(hashQueryIndex + 1) : ''
const hashParams = hashQuery ? new URLSearchParams(hashQuery) : null
const searchToken = urlParams.get('token')
const hashToken = hashParams?.get('token') || null
const urlToken = searchToken || hashToken
if (urlToken) {
  ;(window as any).__LOGIN_TOKEN__ = urlToken
}
if (hashToken && hashParams) {
  hashParams.delete('token')
  if (!searchToken) {
    urlParams.set('token', hashToken)
  }
  const nextSearch = urlParams.toString()
  const nextHashQuery = hashParams.toString()
  const nextHash = `${hashRoutePath || '#/'}${nextHashQuery ? `?${nextHashQuery}` : ''}`
  window.history.replaceState(null, document.title, `${window.location.pathname}${nextSearch ? `?${nextSearch}` : ''}${nextHash}`)
}

const app = createApp(App)
app.use(createPinia())
app.use(i18n)
app.use(router)
app.mount('#app')
