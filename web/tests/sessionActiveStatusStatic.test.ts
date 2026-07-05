import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/sessions.ts', import.meta.url), 'utf8')
const store = readFileSync(new URL('../src/stores/solonclaw/chat.ts', import.meta.url), 'utf8')
const item = readFileSync(new URL('../src/components/solonclaw/chat/SessionListItem.vue', import.meta.url), 'utf8')

assert.ok(api.includes('is_active?: boolean'), 'session summary should type backend is_active')
assert.ok(store.includes('isLive: Boolean(s.is_active)'), 'chat store should map backend active state')
assert.ok(!store.includes('LIVE_BADGE_WINDOW_MS'), 'chat store should not guess live sessions from a local time window')
assert.ok(item.includes('session.isLive'), 'session list item should render backend live state')
assert.ok(item.includes("t('chat.liveMode')"), 'session list item should reuse live label')
