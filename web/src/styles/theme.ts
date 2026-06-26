import type { ThemeConfig } from 'antdv-next'
import { theme } from 'antdv-next'

const fontFamily = 'Inter, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'

const lightTheme: ThemeConfig = {
  algorithm: theme.defaultAlgorithm,
  token: {
    colorPrimary: '#262626',
    colorSuccess: '#22c55e',
    colorWarning: '#f97316',
    colorError: '#ef4444',
    colorInfo: '#262626',
    colorTextBase: '#262626',
    colorBgBase: '#ffffff',
    colorBgContainer: '#ffffff',
    colorBgElevated: '#ffffff',
    colorBgLayout: '#fafafa',
    colorBgMask: 'rgba(38, 38, 38, 0.45)',
    colorBgSpotlight: 'rgba(38, 38, 38, 0.85)',
    colorBorder: '#e5e5e5',
    colorBorderSecondary: '#f5f5f5',
    colorText: '#262626',
    colorTextSecondary: '#525252',
    colorTextTertiary: '#737373',
    colorTextQuaternary: '#a3a3a3',
    colorTextDisabled: '#a3a3a3',
    borderRadius: 10,
    borderRadiusXS: 2,
    borderRadiusSM: 6,
    borderRadiusLG: 14,
    fontFamily,
    boxShadow: '0 1px 3px 0 rgba(0, 0, 0, 0.10), 0 1px 2px -1px rgba(0, 0, 0, 0.10)',
    boxShadowSecondary: '0 4px 6px -1px rgba(0, 0, 0, 0.10), 0 2px 4px -2px rgba(0, 0, 0, 0.10)',
  },
  components: {
    Button: {
      primaryShadow: 'none',
      defaultShadow: 'none',
      dangerShadow: 'none',
      colorPrimary: '#262626',
      defaultBg: '#ffffff',
      defaultColor: '#18181b',
      defaultBorderColor: '#e4e4e7',
      defaultHoverBg: '#f4f4f5',
      defaultHoverColor: '#18181b',
      defaultHoverBorderColor: '#d4d4d8',
      defaultActiveBg: '#e4e4e7',
      defaultActiveBorderColor: '#d4d4d8',
      borderRadius: 6,
    },
    Input: {
      activeShadow: 'none',
      hoverBorderColor: '#a1a1aa',
      activeBorderColor: '#18181b',
      borderRadius: 6,
    },
    Select: {
      optionSelectedBg: '#f4f4f5',
      optionActiveBg: '#fafafa',
      optionSelectedFontWeight: 500,
      borderRadius: 6,
    },
    Modal: {
      borderRadiusLG: 12,
    },
    Drawer: {
      colorBgElevated: '#ffffff',
    },
    Switch: {
      trackHeight: 24,
      trackMinWidth: 44,
      innerMinMargin: 4,
      innerMaxMargin: 24,
    },
    Checkbox: {
      borderRadiusSM: 4,
    },
    Tag: {
      borderRadiusSM: 6,
    },
  },
}

const darkTheme: ThemeConfig = {
  algorithm: theme.darkAlgorithm,
  token: {
    colorPrimary: '#e5e7eb',
    colorSuccess: '#22c55e',
    colorWarning: '#fb923c',
    colorError: '#f87171',
    colorInfo: '#e5e7eb',
    colorTextBase: '#e5e7eb',
    colorBgBase: '#18181b',
    colorBgContainer: '#27272a',
    colorBgElevated: '#27272a',
    colorBgLayout: '#18181b',
    colorBgMask: 'rgba(0, 0, 0, 0.56)',
    colorBgSpotlight: 'rgba(244, 244, 245, 0.14)',
    colorBorder: '#3f3f46',
    colorBorderSecondary: '#27272a',
    colorText: '#e5e7eb',
    colorTextSecondary: '#a1a1aa',
    colorTextTertiary: '#71717a',
    colorTextQuaternary: '#52525b',
    colorTextDisabled: '#52525b',
    borderRadius: 10,
    borderRadiusXS: 2,
    borderRadiusSM: 6,
    borderRadiusLG: 14,
    fontFamily,
    boxShadow: '0 1px 3px 0 rgba(0, 0, 0, 0.35), 0 1px 2px -1px rgba(0, 0, 0, 0.35)',
    boxShadowSecondary: '0 8px 18px -6px rgba(0, 0, 0, 0.45)',
  },
  components: {
    Button: {
      primaryShadow: 'none',
      defaultShadow: 'none',
      dangerShadow: 'none',
      colorPrimary: '#e5e7eb',
      defaultBg: '#27272a',
      defaultColor: '#e5e7eb',
      defaultBorderColor: '#3f3f46',
      defaultHoverBg: '#3f3f46',
      defaultHoverColor: '#ffffff',
      defaultHoverBorderColor: '#52525b',
      defaultActiveBg: '#52525b',
      defaultActiveBorderColor: '#71717a',
      borderRadius: 6,
    },
    Input: {
      activeShadow: 'none',
      hoverBorderColor: '#71717a',
      activeBorderColor: '#e5e7eb',
      borderRadius: 6,
    },
    Select: {
      optionSelectedBg: '#3f3f46',
      optionActiveBg: '#27272a',
      optionSelectedFontWeight: 500,
      borderRadius: 6,
    },
    Modal: {
      borderRadiusLG: 12,
    },
    Drawer: {
      colorBgElevated: '#27272a',
    },
    Switch: {
      trackHeight: 24,
      trackMinWidth: 44,
      innerMinMargin: 4,
      innerMaxMargin: 24,
    },
    Checkbox: {
      borderRadiusSM: 4,
    },
    Tag: {
      borderRadiusSM: 6,
    },
  },
}

export function getThemeConfig(isDark: boolean): ThemeConfig {
  return isDark ? darkTheme : lightTheme
}
