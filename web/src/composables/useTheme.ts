import { getCurrentScope, onScopeDispose, ref, watch, type WatchStopHandle } from 'vue'

export type ThemeMode = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'solonclaw_theme'

const mode = ref<ThemeMode>('system')
const isDark = ref(false)
let initialized = false
let activeThemeScopes = 0
let stopModeWatch: WatchStopHandle | null = null
let stopSystemPreferenceWatch: (() => void) | null = null

function isThemeMode(value: string | null): value is ThemeMode {
  return value === 'light' || value === 'dark' || value === 'system'
}

function readStoredMode(): ThemeMode {
  if (typeof localStorage === 'undefined') return 'system'
  const stored = localStorage.getItem(STORAGE_KEY)
  return isThemeMode(stored) ? stored : 'system'
}

function writeStoredMode(value: ThemeMode) {
  if (typeof localStorage !== 'undefined') {
    localStorage.setItem(STORAGE_KEY, value)
  }
}

function applyTheme(dark: boolean) {
  isDark.value = dark
  if (typeof document !== 'undefined') {
    document.documentElement.classList.toggle('dark', dark)
  }
}

function resolveDark(m: ThemeMode): boolean {
  if (m === 'system') {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
      return false
    }
    return window.matchMedia('(prefers-color-scheme: dark)').matches
  }
  return m === 'dark'
}

function watchSystemPreference(): (() => void) | null {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null
  const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
  const handleChange = () => {
    if (mode.value === 'system') {
      applyTheme(resolveDark('system'))
    }
  }
  if (typeof mediaQuery.addEventListener === 'function') {
    mediaQuery.addEventListener('change', handleChange)
    return () => mediaQuery.removeEventListener('change', handleChange)
  }
  mediaQuery.addListener?.(handleChange)
  return () => mediaQuery.removeListener?.(handleChange)
}

function cleanupThemeListeners() {
  stopSystemPreferenceWatch?.()
  stopSystemPreferenceWatch = null
  stopModeWatch?.()
  stopModeWatch = null
  initialized = false
}

function ensureThemeInitialized() {
  if (initialized) return
  initialized = true
  mode.value = readStoredMode()
  applyTheme(resolveDark(mode.value))
  stopSystemPreferenceWatch = watchSystemPreference()
  stopModeWatch = watch(mode, (newMode) => {
    writeStoredMode(newMode)
    applyTheme(resolveDark(newMode))
  })
}

function releaseThemeScope() {
  activeThemeScopes = Math.max(0, activeThemeScopes - 1)
  if (activeThemeScopes === 0) {
    cleanupThemeListeners()
  }
}

export function useTheme() {
  ensureThemeInitialized()
  if (getCurrentScope()) {
    activeThemeScopes += 1
    onScopeDispose(releaseThemeScope)
  }

  function setMode(m: ThemeMode) {
    mode.value = m
  }

  function toggleTheme() {
    mode.value = isDark.value ? 'light' : 'dark'
  }

  return {
    mode,
    isDark,
    setMode,
    toggleTheme,
  }
}
