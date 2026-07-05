import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import { i18n } from './i18n'
import App from './App.vue'
import { dashboardHashRouteForPath } from './shared/dashboardDirectRoutes'
import { normalizeLoginTokenUrl } from './shared/loginUrlToken'
import 'antdv-next/dist/reset.css'
import './styles/global.scss'

// Apply dark class before mount to prevent FOUC
const savedTheme = localStorage.getItem('solonclaw_theme') || 'system'
const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
if (savedTheme === 'dark' || (savedTheme === 'system' && prefersDark)) {
  document.documentElement.classList.add('dark')
}

// 兼容 hash 路由里的访问令牌：先读取，再规范化 URL，避免路由 query 参与初始化导致空白页。
const loginUrl = normalizeLoginTokenUrl(window.location, dashboardHashRouteForPath)
if (loginUrl.token) {
  window.__LOGIN_TOKEN__ = loginUrl.token
}
if (loginUrl.nextUrl) {
  window.history.replaceState(null, document.title, loginUrl.nextUrl)
}

const app = createApp(App)
app.use(createPinia())
app.use(i18n)
app.use(router)
app.mount('#app')
