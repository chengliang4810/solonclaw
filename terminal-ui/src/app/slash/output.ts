import type { SlashExecResponse } from '../../gatewayTypes.js'

interface SlashOutputTarget {
  page: (text: string, title?: string) => void
  sys: (text: string) => void
}

const SLASH_PAGE_CHAR_THRESHOLD = 180
const SLASH_PAGE_LINE_THRESHOLD = 2

/**
 * 对齐 slash worker 的 TUI 展示边界：短结果保持单行状态流，长文本进入分页面板，避免命令实现各自维护阈值。
 */
export const renderSlashExecOutput = (
  transcript: SlashOutputTarget,
  response: null | SlashExecResponse | undefined,
  fallback: string,
  title: string
) => {
  const body = response?.output || fallback
  const text = response?.warning ? `warning: ${response.warning}\n${body}` : body

  const shouldPage =
    text.length > SLASH_PAGE_CHAR_THRESHOLD ||
    text.split('\n').filter(line => line.trim().length > 0).length > SLASH_PAGE_LINE_THRESHOLD

  if (shouldPage) {
    transcript.page(text, title)

    return
  }

  transcript.sys(text)
}
