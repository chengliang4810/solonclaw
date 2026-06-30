export type GoalCommandAction = 'pause' | 'resume' | 'clear'
export type GoalStatus = 'active' | 'paused' | 'done'
export type ContextSessionMenuKey = 'pin' | 'rename' | 'copy-id'

export interface GoalCommandActionMeta {
  readonly action: GoalCommandAction
}

export interface ContextSessionMenuAction {
  readonly key: ContextSessionMenuKey
  readonly labelKey: string
}

export interface ContextSessionMenuItem {
  readonly key: ContextSessionMenuKey
  readonly label: string
}

export type ChatDisplayTranslator = (key: string, values?: Readonly<Record<string, unknown>>) => string

export const GOAL_COMMAND_ACTIONS: readonly GoalCommandActionMeta[] = [
  { action: 'pause' },
  { action: 'resume' },
  { action: 'clear' },
] as const

export const CONTEXT_SESSION_MENU_ACTIONS: readonly ContextSessionMenuAction[] = [
  { key: 'pin', labelKey: 'chat.pin' },
  { key: 'rename', labelKey: 'chat.rename' },
  { key: 'copy-id', labelKey: 'chat.copySessionId' },
] as const

const GOAL_STATUS_LABEL_KEYS: Readonly<Record<GoalStatus, string>> = {
  active: 'chat.goalStatusActive',
  paused: 'chat.goalStatusPaused',
  done: 'chat.goalStatusDone',
} as const

const GOAL_STATUS_SET: ReadonlySet<string> = new Set(['active', 'paused', 'done'])

export function goalCommandText(action: GoalCommandAction): string {
  return `/goal ${action}`
}

export function goalStatusLabel(status: string, t: ChatDisplayTranslator): string {
  return isGoalStatus(status) ? t(GOAL_STATUS_LABEL_KEYS[status]) : t('chat.goalStatusUnknown', { status })
}

export function sessionContextMenuItems(
  isPinned: boolean,
  t: ChatDisplayTranslator,
): ContextSessionMenuItem[] {
  return CONTEXT_SESSION_MENU_ACTIONS.map(action => ({
    key: action.key,
    label: t(action.key === 'pin' && isPinned ? 'chat.unpin' : action.labelKey),
  }))
}

function isGoalStatus(status: string): status is GoalStatus {
  return GOAL_STATUS_SET.has(status)
}
