import type { Theme } from '../theme.js'

/** 会话切换器的展示文案和颜色映射保持纯函数，避免交互组件重复维护这些 UI 规则。 */
export const activeSessionCountLabel = (count: number) =>
  `${count} live ${count === 1 ? 'session' : 'sessions'}`

export const sessionsCountLabel = (liveCount: number, resumableCount: number) =>
  `${liveCount} live · ${resumableCount} resumable`

export type OrchestratorHintRole = 'hotkey' | 'label' | 'text'

export interface OrchestratorHintSegment {
  role: OrchestratorHintRole
  text: string
}

export const resumeRowContextHintSegments: OrchestratorHintSegment[] = [
  { role: 'label', text: 'Resumable:' },
  { role: 'text', text: ' ' },
  { role: 'hotkey', text: 'Enter' },
  { role: 'text', text: ' resume · ' },
  { role: 'hotkey', text: 'd' },
  { role: 'text', text: ' delete' }
]

export const orchestratorContextHintSegments = (newSelected: boolean): OrchestratorHintSegment[] =>
  newSelected
    ? [
        { role: 'label', text: 'New row:' },
        { role: 'text', text: ' type prompt · ' },
        { role: 'hotkey', text: 'Enter' },
        { role: 'text', text: ' start · ' },
        { role: 'hotkey', text: 'Tab' },
        { role: 'text', text: ' model' }
      ]
    : [
        { role: 'label', text: 'Session row:' },
        { role: 'text', text: ' ' },
        { role: 'hotkey', text: 'Enter' },
        { role: 'text', text: ' switch · ' },
        { role: 'hotkey', text: 'Ctrl+D' },
        { role: 'text', text: ' close' }
      ]

export const orchestratorGlobalHotkeyHintSegments: OrchestratorHintSegment[] = [
  { role: 'hotkey', text: '↑↓' },
  { role: 'text', text: ' move · ' },
  { role: 'hotkey', text: 'Ctrl+N' },
  { role: 'text', text: ' new · ' },
  { role: 'hotkey', text: 'Ctrl+R' },
  { role: 'text', text: ' refresh · ' },
  { role: 'hotkey', text: 'Esc' },
  { role: 'text', text: ' close' }
]

const hintText = (segments: readonly OrchestratorHintSegment[]) => segments.map(segment => segment.text).join('')

export const orchestratorContextHint = (newSelected: boolean) => hintText(orchestratorContextHintSegments(newSelected))

export const orchestratorGlobalHotkeyHint = hintText(orchestratorGlobalHotkeyHintSegments)

export const orchestratorHintSegmentColor = (t: Theme, role: OrchestratorHintRole) => {
  if (role === 'hotkey') {
    return t.color.accent
  }

  if (role === 'label') {
    return t.color.label
  }

  return t.color.muted
}

export const selectedSessionRowStyle = (t: Theme) => ({
  backgroundColor: t.color.selectionBg,
  color: t.color.text
})

export const newSessionMarkerColor = (t: Theme, selected: boolean) =>
  selected ? selectedSessionRowStyle(t).color : t.color.label
