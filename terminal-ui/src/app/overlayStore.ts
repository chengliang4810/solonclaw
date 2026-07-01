import { atom, computed } from 'nanostores'

import type { OverlayState } from './interfaces.js'

const buildOverlayState = (): OverlayState => ({
  agents: false,
  agentsInitialHistoryIndex: 0,
  approval: null,
  channelSetup: false,
  clarify: null,
  confirm: null,
  modelPicker: false,
  pager: null,
  secret: null,
  setupPanel: false,
  sessions: false,
  skillsHub: false,
  sudo: null
})

const PAGER_CLOSE_INPUT_SUPPRESSION_MS = 500
let pagerClosedByKeyboardAt = 0

export const $overlayState = atom<OverlayState>(buildOverlayState())

export const $isBlocked = computed(
  $overlayState,
  ({ agents, approval, channelSetup, clarify, confirm, modelPicker, pager, secret, setupPanel, sessions, skillsHub, sudo }) =>
    Boolean(
      agents ||
        approval ||
        channelSetup ||
        clarify ||
        confirm ||
        modelPicker ||
        pager ||
        secret ||
        setupPanel ||
        sessions ||
        skillsHub ||
        sudo
    )
)

export const getOverlayState = () => $overlayState.get()

export const patchOverlayState = (next: Partial<OverlayState> | ((state: OverlayState) => OverlayState)) =>
  $overlayState.set(typeof next === 'function' ? next($overlayState.get()) : { ...$overlayState.get(), ...next })

export const notePagerClosedByKeyboard = (now = Date.now()) => {
  pagerClosedByKeyboardAt = now
}

export const consumePagerCloseInputSuppression = (input: string, now = Date.now()) => {
  if (!pagerClosedByKeyboardAt) {
    return false
  }

  const shouldSuppress = input === 'q' && now - pagerClosedByKeyboardAt <= PAGER_CLOSE_INPUT_SUPPRESSION_MS
  pagerClosedByKeyboardAt = 0

  return shouldSuppress
}

export const dismissApprovalIfCurrent = (approvalId: string | undefined) =>
  patchOverlayState(state => {
    if (!state.approval || state.approval.approvalId !== approvalId) {
      return state
    }

    return { ...state, approval: null }
  })

/** Full reset — used by session/turn teardown and tests. */
export const resetOverlayState = () => {
  pagerClosedByKeyboardAt = 0
  $overlayState.set(buildOverlayState())
}

/**
 * Soft reset: drop FLOW-scoped overlays (approval / clarify / confirm / sudo
 * / secret / pager) but PRESERVE user-toggled ones — agents dashboard, model
 * picker, skills hub, sessions overlay.  Those are opened deliberately and
 * shouldn't vanish when a turn ends.  Called from turnController.idle() on
 * every turn completion / interrupt; the old "reset everything" behaviour
 * silently closed /agents the moment delegation finished.
 */
export const resetFlowOverlays = () =>
  $overlayState.set({
    ...buildOverlayState(),
    agents: $overlayState.get().agents,
    agentsInitialHistoryIndex: $overlayState.get().agentsInitialHistoryIndex,
    channelSetup: $overlayState.get().channelSetup,
    modelPicker: $overlayState.get().modelPicker,
    setupPanel: $overlayState.get().setupPanel,
    sessions: $overlayState.get().sessions,
    skillsHub: $overlayState.get().skillsHub
  })
