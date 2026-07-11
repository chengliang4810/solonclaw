import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const sessionsApi = readFileSync(new URL('../src/api/solonclaw/sessions.ts', import.meta.url), 'utf8')
const chatApi = readFileSync(new URL('../src/api/solonclaw/chat.ts', import.meta.url), 'utf8')
const chatStore = readFileSync(new URL('../src/stores/solonclaw/chat.ts', import.meta.url), 'utf8')
const chatPanel = readFileSync(new URL('../src/components/solonclaw/chat/ChatPanel.vue', import.meta.url), 'utf8')
const sessionItem = readFileSync(new URL('../src/components/solonclaw/chat/SessionListItem.vue', import.meta.url), 'utf8')

assert.ok(sessionsApi.includes("const endpoint = allProfiles ? '/api/profiles/sessions' : '/api/sessions'"))
assert.ok(sessionsApi.includes("params.set('min_messages', '1')"))
assert.ok(sessionsApi.includes("params.set('archived', 'exclude')"))
assert.ok(sessionsApi.includes("params.set('order', 'recent')"))
assert.ok(sessionsApi.includes("params.set('profile', 'all')"))
assert.match(sessionsApi, /deleteSession\(id: string, profile\?: string\)/)
assert.match(sessionsApi, /renameSession\(id: string, title: string, profile\?: string\)/)

assert.ok(chatApi.includes('profile?: string'))
assert.ok(chatApi.includes("withProfile('/api/chat/uploads', profile)"))
assert.ok(chatApi.includes("withProfile(`/api/chat/runs/${encodeURIComponent(runId)}/cancel`, profile)"))
assert.ok(chatApi.includes("withProfile(`/api/chat/runs/${encodeURIComponent(runId)}/events`, profile)"))

assert.ok(chatStore.includes("const profile = s.profile || fallbackProfile || 'default'"))
assert.ok(chatStore.includes('deleteSessionApi(target.id, target.profile)'))
assert.ok(chatStore.includes('fetchSession(target.id, target.profile)'))
assert.ok(chatStore.includes('profile: startingProfile'))
assert.ok(chatStore.includes('streamRunEvents(') && chatStore.includes('startingProfile,'))
assert.ok(chatStore.includes('key: profileSessionIdentity(s.id, profile)'))
assert.ok(chatStore.includes('activeSessionKey'))
assert.ok(chatPanel.includes('renameSession(target.id, renameValue.value.trim(), target.profile)'))
assert.ok(chatPanel.includes(':key="s.key"'))
assert.ok(chatPanel.includes(':active="s.key === chatStore.activeSessionKey"'))
assert.ok(sessionItem.includes('session-item-profile'))
