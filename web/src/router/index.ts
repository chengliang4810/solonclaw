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
      name: 'SolonClaw.chat',
      component: () => import('@/views/solonclaw/ChatView.vue'),
    },
    {
      path: '/solonclaw/agents',
      name: 'SolonClaw.agents',
      component: () => import('@/views/solonclaw/AgentsView.vue'),
    },
    {
      path: '/solonclaw/jobs',
      name: 'SolonClaw.jobs',
      component: () => import('@/views/solonclaw/JobsView.vue'),
    },
    {
      path: '/solonclaw/models',
      name: 'SolonClaw.models',
      component: () => import('@/views/solonclaw/ModelsView.vue'),
    },
    {
      path: '/solonclaw/persona/journal',
      name: 'SolonClaw.persona.journal',
      component: () => import('@/views/solonclaw/PersonaDiaryView.vue'),
    },
    {
      path: '/solonclaw/persona/:key',
      name: 'SolonClaw.persona.file',
      component: () => import('@/views/solonclaw/PersonaFileView.vue'),
    },
    {
      path: '/solonclaw/logs',
      name: 'SolonClaw.logs',
      component: () => import('@/views/solonclaw/LogsView.vue'),
    },
    {
      path: '/solonclaw/usage',
      name: 'SolonClaw.usage',
      component: () => import('@/views/solonclaw/UsageView.vue'),
    },
    {
      path: '/solonclaw/runs',
      name: 'SolonClaw.runs',
      component: () => import('@/views/solonclaw/RunsView.vue'),
    },
    {
      path: '/solonclaw/skills',
      name: 'SolonClaw.skills',
      component: () => import('@/views/solonclaw/SkillsView.vue'),
    },
    {
      path: '/solonclaw/settings',
      name: 'SolonClaw.settings',
      component: () => import('@/views/solonclaw/SettingsView.vue'),
    },
    {
      path: '/solonclaw/diagnostics',
      name: 'SolonClaw.diagnostics',
      component: () => import('@/views/solonclaw/DiagnosticsView.vue'),
    },
    {
      path: '/solonclaw/channels',
      name: 'SolonClaw.channels',
      component: () => import('@/views/solonclaw/ChannelsView.vue'),
    },
    {
      path: '/solonclaw/mcp',
      name: 'SolonClaw.mcp',
      component: () => import('@/views/solonclaw/McpView.vue'),
    },
    {
      path: '/solonclaw/files',
      name: 'SolonClaw.files',
      component: () => import('@/views/solonclaw/FilesView.vue'),
    },
  ],
})

router.beforeEach((to, _from, next) => {
  // Public pages don't need auth
  if (to.meta.public) {
    // Already has key, skip login
    if (to.name === 'login' && hasApiKey()) {
      next({ path: '/solonclaw/chat' })
      return
    }
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
