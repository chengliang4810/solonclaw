import { createRouter, createWebHashHistory } from 'vue-router'
import { hasApiKey } from '@/api/client'

const STALE_CHUNK_RELOAD_KEY = 'solonclaw_stale_chunk_reload_at'
let staleChunkReloaded = false

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/solonclaw/chat',
      name: 'solonclaw.chat',
      component: () => import('@/views/solonclaw/ChatView.vue'),
    },
    {
      path: '/solonclaw/agents',
      name: 'solonclaw.agents',
      component: () => import('@/views/solonclaw/AgentsView.vue'),
    },
    {
      path: '/solonclaw/jobs',
      name: 'solonclaw.jobs',
      component: () => import('@/views/solonclaw/JobsView.vue'),
    },
    {
      path: '/solonclaw/models',
      name: 'solonclaw.models',
      component: () => import('@/views/solonclaw/ModelsView.vue'),
    },
    {
      path: '/solonclaw/persona/journal',
      name: 'solonclaw.persona.journal',
      component: () => import('@/views/solonclaw/PersonaDiaryView.vue'),
    },
    {
      path: '/solonclaw/persona/:key',
      name: 'solonclaw.persona.file',
      component: () => import('@/views/solonclaw/PersonaFileView.vue'),
    },
    {
      path: '/solonclaw/logs',
      name: 'solonclaw.logs',
      component: () => import('@/views/solonclaw/LogsView.vue'),
    },
    {
      path: '/solonclaw/usage',
      name: 'solonclaw.usage',
      component: () => import('@/views/solonclaw/UsageView.vue'),
    },
    {
      path: '/solonclaw/runs',
      name: 'solonclaw.runs',
      component: () => import('@/views/solonclaw/RunsView.vue'),
    },
    {
      path: '/solonclaw/skills',
      name: 'solonclaw.skills',
      component: () => import('@/views/solonclaw/SkillsView.vue'),
    },
    {
      path: '/solonclaw/settings',
      name: 'solonclaw.settings',
      component: () => import('@/views/solonclaw/SettingsView.vue'),
    },
    {
      path: '/solonclaw/diagnostics',
      name: 'solonclaw.diagnostics',
      component: () => import('@/views/solonclaw/DiagnosticsView.vue'),
    },
    {
      path: '/solonclaw/tui-runtime',
      name: 'solonclaw.tuiRuntime',
      component: () => import('@/views/solonclaw/TuiRuntimeView.vue'),
    },
    {
      path: '/solonclaw/curator',
      name: 'solonclaw.curator',
      component: () => import('@/views/solonclaw/CuratorView.vue'),
    },
    {
      path: '/solonclaw/channels',
      name: 'solonclaw.channels',
      component: () => import('@/views/solonclaw/ChannelsView.vue'),
    },
    {
      path: '/solonclaw/gateways',
      name: 'solonclaw.gateways',
      component: () => import('@/views/solonclaw/GatewaysView.vue'),
    },
    {
      path: '/solonclaw/mcp',
      name: 'solonclaw.mcp',
      component: () => import('@/views/solonclaw/McpView.vue'),
    },
    {
      path: '/solonclaw/files',
      name: 'solonclaw.files',
      component: () => import('@/views/solonclaw/FilesView.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: { name: 'solonclaw.chat' },
    },
  ],
})

router.beforeEach((to, _from, next) => {
  // Public pages don't need auth
  if (to.meta.public) {
    next()
    return
  }

  // All other pages require token
  if (!hasApiKey()) {
    next({ name: 'login' })
    return
  }

  next()
})

router.onError((error) => {
  if (!isStaleChunkError(error) || !shouldReloadForStaleChunk()) {
    return
  }
  window.location.reload()
})

function isStaleChunkError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error || '')
  return /Failed to fetch dynamically imported module|Importing a module script failed|Loading chunk \d+ failed|error loading dynamically imported module/i.test(message)
}

function shouldReloadForStaleChunk(): boolean {
  if (staleChunkReloaded) {
    return false
  }
  staleChunkReloaded = true
  try {
    const lastReloadAt = Number(sessionStorage.getItem(STALE_CHUNK_RELOAD_KEY) || '0')
    const now = Date.now()
    if (now - lastReloadAt < 10_000) {
      return false
    }
    sessionStorage.setItem(STALE_CHUNK_RELOAD_KEY, String(now))
  } catch {
    // Browsers can disable sessionStorage; a single in-memory reload guard still prevents loops.
  }
  return true
}

export default router
