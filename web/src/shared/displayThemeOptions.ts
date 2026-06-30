import type { ThemeMode } from '@/composables/useTheme'

export interface DisplayThemeOption {
  readonly labelKey: string
  readonly value: ThemeMode
}

export interface TranslatedDisplayThemeOption {
  readonly label: string
  readonly value: ThemeMode
}

export type DisplayThemeTranslator = (key: string) => string

export const DISPLAY_THEME_OPTIONS: readonly DisplayThemeOption[] = [
  { labelKey: 'settings.display.themeLight', value: 'light' },
  { labelKey: 'settings.display.themeDark', value: 'dark' },
  { labelKey: 'settings.display.themeSystem', value: 'system' },
] as const

const DISPLAY_THEME_VALUE_SET: ReadonlySet<string> = new Set(
  DISPLAY_THEME_OPTIONS.map(option => option.value),
)

export function translateDisplayThemeOptions(t: DisplayThemeTranslator): TranslatedDisplayThemeOption[] {
  return DISPLAY_THEME_OPTIONS.map(option => ({
    label: t(option.labelKey),
    value: option.value,
  }))
}

export function isDisplayThemeMode(value: string): value is ThemeMode {
  return DISPLAY_THEME_VALUE_SET.has(value)
}
