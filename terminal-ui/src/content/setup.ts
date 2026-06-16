import type { PanelSection } from '../types.js'

export const SETUP_REQUIRED_TITLE = '需要先完成设置'

export const buildSetupRequiredSections = (): PanelSection[] => [
  {
    text: 'SolonClaw 在 TUI 中开始会话前，需要先配置模型提供方。'
  },
  {
    rows: [
      ['/model', '直接配置提供方与模型'],
      ['/setup', '直接打开首次初始化向导'],
      ['Ctrl+C', '退出后手动执行 `solonclaw setup`']
    ],
    title: '可执行操作'
  }
]
