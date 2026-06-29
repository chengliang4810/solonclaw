import { createRouter, createWebHashHistory } from 'vue-router'
import { hasApiKey } from '@/api/client'

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

export default router
