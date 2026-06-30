export interface SidebarNavItem {
  readonly key: string
  readonly labelKey: string
  readonly icon: string
}

export interface PersonaNavItem {
  readonly key: string
  readonly metaKey: string
  readonly icon: string
}

export const PRIMARY_NAV_ITEMS: readonly SidebarNavItem[] = [
  {
    key: 'solonclaw.chat',
    labelKey: 'sidebar.chat',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" /></svg>',
  },
  {
    key: 'solonclaw.agents',
    labelKey: 'sidebar.agents',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="7" r="4" /><path d="M5.5 21a6.5 6.5 0 0 1 13 0" /><path d="M17 11.5l2 2 3-4" /></svg>',
  },
  {
    key: 'solonclaw.skills',
    labelKey: 'sidebar.skills',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 2 7 12 12 22 7 12 2" /><polyline points="2 17 12 22 22 17" /><polyline points="2 12 12 17 22 12" /></svg>',
  },
  {
    key: 'solonclaw.jobs',
    labelKey: 'sidebar.jobs',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="18" rx="2" ry="2" /><line x1="16" y1="2" x2="16" y2="6" /><line x1="8" y1="2" x2="8" y2="6" /><line x1="3" y1="10" x2="21" y2="10" /></svg>',
  },
  {
    key: 'solonclaw.channels',
    labelKey: 'sidebar.channels',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" /></svg>',
  },
  {
    key: 'solonclaw.models',
    labelKey: 'sidebar.models',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3" /><path d="M12 1v4" /><path d="M12 19v4" /><path d="M1 12h4" /><path d="M19 12h4" /><path d="M4.22 4.22l2.83 2.83" /><path d="M16.95 16.95l2.83 2.83" /><path d="M4.22 19.78l2.83-2.83" /><path d="M16.95 7.05l2.83-2.83" /></svg>',
  },
] as const

export const PERSONA_NAV_ITEMS: readonly PersonaNavItem[] = [
  {
    key: 'agents',
    metaKey: 'agents',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M8 6h13"/><path d="M8 12h13"/><path d="M8 18h13"/><path d="M3 6h.01"/><path d="M3 12h.01"/><path d="M3 18h.01"/></svg>',
  },
  {
    key: 'memory',
    metaKey: 'memory',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9.5 3.5a3 3 0 0 0-3 3v1.1a2.4 2.4 0 0 1-.7 1.7l-.1.1a3.7 3.7 0 0 0 0 5.2l.1.1a2.4 2.4 0 0 1 .7 1.7v1.1a3 3 0 0 0 5.1 2.1l.4-.4.4.4a3 3 0 0 0 5.1-2.1v-1.1a2.4 2.4 0 0 1 .7-1.7l.1-.1a3.7 3.7 0 0 0 0-5.2l-.1-.1a2.4 2.4 0 0 1-.7-1.7V6.5a3 3 0 0 0-5.1-2.1l-.4.4-.4-.4a3 3 0 0 0-2.1-.9z"/><path d="M9.5 10.25c.7-.85 1.6-1.25 2.5-1.25s1.8.4 2.5 1.25"/><path d="M9.5 13.75c.7.85 1.6 1.25 2.5 1.25s1.8-.4 2.5-1.25"/><path d="M12 9v6"/></svg>',
  },
  {
    key: 'journal',
    metaKey: 'memory_today',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="18" rx="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/><path d="M8 14h8"/><path d="M8 18h5"/></svg>',
  },
  {
    key: 'soul',
    metaKey: 'soul',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 21s-6.716-4.184-9.193-7.252C.182 10.61 1.518 5.873 5.66 4.677A5.38 5.38 0 0 1 12 6.09a5.38 5.38 0 0 1 6.34-1.413c4.142 1.196 5.478 5.933 2.853 9.071C18.716 16.816 12 21 12 21z"/></svg>',
  },
  {
    key: 'identity',
    metaKey: 'identity',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="3" width="16" height="18" rx="2"/><circle cx="12" cy="10" r="3"/><path d="M8 17c1.2-1.333 2.533-2 4-2s2.8.667 4 2"/></svg>',
  },
  {
    key: 'user',
    metaKey: 'user',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>',
  },
  {
    key: 'tools',
    metaKey: 'tools',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.2 2.2-3.2-3.2 2.4-2z"/></svg>',
  },
  {
    key: 'heartbeat',
    metaKey: 'heartbeat',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M19.5 13.5A7.5 7.5 0 1 1 10.5 4"/><path d="M12 2v4h4"/><path d="M8 13h2l1.5-3 3 6 1.5-3h2"/></svg>',
  },
] as const

export const MONITORING_NAV_ITEMS: readonly SidebarNavItem[] = [
  {
    key: 'solonclaw.logs',
    labelKey: 'sidebar.logs',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><polyline points="14 2 14 8 20 8" /><line x1="16" y1="13" x2="8" y2="13" /><line x1="16" y1="17" x2="8" y2="17" /><polyline points="10 9 9 9 8 9" /></svg>',
  },
  {
    key: 'solonclaw.usage',
    labelKey: 'sidebar.usage',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="12" width="4" height="9" rx="1" /><rect x="10" y="7" width="4" height="14" rx="1" /><rect x="17" y="3" width="4" height="18" rx="1" /></svg>',
  },
  {
    key: 'solonclaw.runs',
    labelKey: 'sidebar.runs',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19V5" /><path d="M4 19h16" /><path d="M8 16l3-4 3 2 4-7" /></svg>',
  },
  {
    key: 'solonclaw.gateways',
    labelKey: 'sidebar.gateways',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7h16" /><path d="M4 17h16" /><circle cx="8" cy="7" r="2" /><circle cx="16" cy="17" r="2" /></svg>',
  },
] as const
