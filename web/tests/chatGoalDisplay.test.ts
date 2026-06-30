import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  CONTEXT_SESSION_MENU_ACTIONS,
  GOAL_COMMAND_ACTIONS,
  goalCommandText,
  goalStatusLabel,
  sessionContextMenuItems,
} from '../src/shared/chatGoalDisplay.ts'

const chatPanel = readFileSync(
  new URL('../src/components/solonclaw/chat/ChatPanel.vue', import.meta.url),
  'utf8',
)

assert.deepEqual(GOAL_COMMAND_ACTIONS.map(item => item.action), ['pause', 'resume', 'clear'])
assert.equal(goalCommandText('pause'), '/goal pause')
assert.equal(goalCommandText('resume'), '/goal resume')
assert.equal(goalCommandText('clear'), '/goal clear')

assert.equal(goalStatusLabel('active', key => `label:${key}`), 'label:chat.goalStatusActive')
assert.equal(goalStatusLabel('paused', key => `label:${key}`), 'label:chat.goalStatusPaused')
assert.equal(goalStatusLabel('done', key => `label:${key}`), 'label:chat.goalStatusDone')
assert.equal(
  goalStatusLabel('blocked', (key, values) => `label:${key}:${values?.status ?? ''}`),
  'label:chat.goalStatusUnknown:blocked',
)

assert.deepEqual(CONTEXT_SESSION_MENU_ACTIONS.map(item => item.key), ['pin', 'rename', 'copy-id'])
assert.deepEqual(
  sessionContextMenuItems(true, key => `label:${key}`).map(item => item.label),
  ['label:chat.unpin', 'label:chat.rename', 'label:chat.copySessionId'],
)
assert.deepEqual(
  sessionContextMenuItems(false, key => `label:${key}`).map(item => item.label),
  ['label:chat.pin', 'label:chat.rename', 'label:chat.copySessionId'],
)

assert.ok(!chatPanel.includes('const statusMap'), 'ChatPanel should not inline goal status labels')
assert.ok(!chatPanel.includes('const contextMenuOptions = computed(() => ['), 'ChatPanel should not inline session menu metadata')
assert.ok(chatPanel.includes('goalStatusLabel(goal.status, t)'), 'ChatPanel should reuse shared goal status labels')
assert.ok(chatPanel.includes('goalCommandText(action)'), 'ChatPanel should reuse shared goal command text')
assert.ok(chatPanel.includes('sessionContextMenuItems(contextSessionPinned.value, t)'), 'ChatPanel should reuse shared context menu metadata')
