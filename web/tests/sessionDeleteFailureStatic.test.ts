import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const store = readFileSync(new URL('../src/stores/solonclaw/chat.ts', import.meta.url), 'utf8')
const panel = readFileSync(new URL('../src/components/solonclaw/chat/ChatPanel.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

const storeDelete = store.slice(
  store.indexOf('async function deleteSession(sessionId: string)'),
  store.indexOf('function getSessionMsgs'),
)

assert.match(
  storeDelete,
  /async function deleteSession\(sessionId: string\): Promise<boolean>/,
  'store deleteSession should return the backend delete result',
)
assert.ok(
  storeDelete.includes('const ok = await deleteSessionApi(sessionId)'),
  'store deleteSession should inspect the API boolean result',
)
assert.ok(
  storeDelete.includes('if (!ok) return false'),
  'store deleteSession should stop before local mutation when backend delete fails',
)
assert.ok(
  storeDelete.trim().endsWith('return true\n  }'),
  'store deleteSession should return true after local state is updated',
)

const panelDelete = panel.slice(
  panel.indexOf('async function handleDeleteSession(id: string)'),
  panel.indexOf('const contextSessionId'),
)

assert.ok(
  panelDelete.includes('const ok = await chatStore.deleteSession(id)'),
  'ChatPanel delete handler should await the store result',
)
assert.ok(
  panelDelete.indexOf('sessionBrowserPrefsStore.removePinned(id)') > panelDelete.indexOf('if (!ok)'),
  'ChatPanel should keep pinned state when delete fails',
)
assert.ok(
  panelDelete.includes("message.error(t('chat.deleteFailed'))"),
  'ChatPanel should show a localized delete failure message',
)
assert.ok(
  panelDelete.indexOf("message.success(t('chat.sessionDeleted'))") > panelDelete.indexOf('sessionBrowserPrefsStore.removePinned(id)'),
  'ChatPanel should show success only after the local delete completes',
)

assert.ok(zh.includes("deleteFailed: '删除会话失败'"), 'Chinese locale should label chat session delete failures')
assert.ok(en.includes("deleteFailed: 'Failed to delete session'"), 'English locale should label chat session delete failures')
