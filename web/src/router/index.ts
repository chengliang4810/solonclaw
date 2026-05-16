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
      path: '/jimuqu/chat',
      name: 'Jimuqu.chat',
      component: () => import('@/views/jimuqu/ChatView.vue'),
    },
    {
      path: '/jimuqu/agents',
      name: 'Jimuqu.agents',
      component: () => import('@/views/jimuqu/AgentsView.vue'),
    },
    {
      path: '/jimuqu/jobs',
      name: 'Jimuqu.jobs',
      component: () => import('@/views/jimuqu/JobsView.vue'),
    },
    {
      path: '/jimuqu/kanban',
      name: 'Jimuqu.kanban',
      component: () => import('@/views/jimuqu/KanbanView.vue'),
    },
    {
      path: '/jimuqu/models',
      name: 'Jimuqu.models',
      component: () => import('@/views/jimuqu/ModelsView.vue'),
    },
    {
      path: '/jimuqu/persona/journal',
      name: 'Jimuqu.persona.journal',
      component: () => import('@/views/jimuqu/PersonaDiaryView.vue'),
    },
    {
      path: '/jimuqu/persona/:key',
      name: 'Jimuqu.persona.file',
      component: () => import('@/views/jimuqu/PersonaFileView.vue'),
    },
    {
      path: '/jimuqu/logs',
      name: 'Jimuqu.logs',
      component: () => import('@/views/jimuqu/LogsView.vue'),
    },
    {
      path: '/jimuqu/usage',
      name: 'Jimuqu.usage',
      component: () => import('@/views/jimuqu/UsageView.vue'),
    },
    {
      path: '/jimuqu/runs',
      name: 'Jimuqu.runs',
      component: () => import('@/views/jimuqu/RunsView.vue'),
    },
    {
      path: '/jimuqu/skills',
      name: 'Jimuqu.skills',
      component: () => import('@/views/jimuqu/SkillsView.vue'),
    },
    {
      path: '/jimuqu/memory',
      name: 'Jimuqu.memory',
      redirect: '/jimuqu/persona/memory',
    },
    {
      path: '/jimuqu/settings',
      name: 'Jimuqu.settings',
      component: () => import('@/views/jimuqu/SettingsView.vue'),
    },
    {
      path: '/jimuqu/diagnostics',
      name: 'Jimuqu.diagnostics',
      component: () => import('@/views/jimuqu/DiagnosticsView.vue'),
    },
    {
      path: '/jimuqu/gateways',
      name: 'Jimuqu.gateways',
      redirect: '/jimuqu/channels',
    },
    {
      path: '/jimuqu/channels',
      name: 'Jimuqu.channels',
      component: () => import('@/views/jimuqu/ChannelsView.vue'),
    },
    {
      path: '/jimuqu/terminal',
      name: 'Jimuqu.terminal',
      component: () => import('@/views/jimuqu/TerminalView.vue'),
    },
    {
      path: '/jimuqu/tui',
      name: 'Jimuqu.tui',
      component: () => import('@/views/jimuqu/TuiView.vue'),
    },
    {
      path: '/jimuqu/mcp',
      name: 'Jimuqu.mcp',
      component: () => import('@/views/jimuqu/McpView.vue'),
    },
    {
      path: '/jimuqu/files',
      name: 'Jimuqu.files',
      component: () => import('@/views/jimuqu/FilesView.vue'),
    },
  ],
})

router.beforeEach((to, _from, next) => {
  // Public pages don't need auth
  if (to.meta.public) {
    // Already has key, skip login
    if (to.name === 'login' && hasApiKey()) {
      next({ path: '/jimuqu/chat' })
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
