import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const chatStore = readFileSync(new URL('../src/stores/solonclaw/chat.ts', import.meta.url), 'utf8')

for (const snapshot of [
  'startingSessionKey',
  'startingSessionId',
  'startingProfile',
  'startingRouteToken',
  'startingModel',
]) {
  assert.ok(chatStore.includes(snapshot), `submit should snapshot ${snapshot}`)
}

assert.ok(chatStore.includes('const sessionContextDrifted = () =>'))
assert.ok(chatStore.includes('createdSessionKey = session.key'))
assert.ok(chatStore.includes('if (activeSessionKey.value !== createdSessionKey) return false'))
assert.ok(chatStore.includes('if (sessionContextDrifted()) return abortForSessionSwitch()'))
assert.ok(chatStore.includes('target.messages = target.messages.filter(message => message.id !== userMsg.id)'))
assert.ok(chatStore.includes('target.title = previousTitle'))
assert.ok(chatStore.includes('profile: startingProfile'))
assert.ok(chatStore.includes('session_id: startingSessionId'))
assert.ok(chatStore.includes('model: startingModel'))
assert.ok(chatStore.includes('startingProfile,\n          )'))

const uploadIndex = chatStore.indexOf('await uploadChatFiles(')
const driftIndex = chatStore.indexOf('if (sessionContextDrifted()) return abortForSessionSwitch()', uploadIndex)
const startRunIndex = chatStore.indexOf('const run = await startRun(', uploadIndex)
assert.ok(uploadIndex >= 0 && driftIndex > uploadIndex && startRunIndex > driftIndex)

const submitBody = chatStore.slice(chatStore.indexOf('async function sendMessage'), chatStore.indexOf('async function sendSlashCommand'))
assert.equal(submitBody.includes('activeSession.value?.model'), false)
