import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'

const sharedModalFile = new URL('../src/components/solonclaw/models/DeviceCodeLoginModal.vue', import.meta.url)
const codexModalFile = new URL('../src/components/solonclaw/models/CodexLoginModal.vue', import.meta.url)
const nousModalFile = new URL('../src/components/solonclaw/models/NousLoginModal.vue', import.meta.url)

assert.ok(existsSync(sharedModalFile), 'device-code login should have one shared modal component')

const codexModal = readFileSync(codexModalFile, 'utf8')
const nousModal = readFileSync(nousModalFile, 'utf8')

assert.ok(codexModal.includes('DeviceCodeLoginModal'), 'Codex login should delegate to shared device-code modal')
assert.ok(nousModal.includes('DeviceCodeLoginModal'), 'Nous login should delegate to shared device-code modal')

assert.ok(!codexModal.includes('setTimeout(async () =>'), 'Codex wrapper should not keep its own polling loop')
assert.ok(!nousModal.includes('setTimeout(async () =>'), 'Nous wrapper should not keep its own polling loop')
assert.ok(!codexModal.includes('<Modal'), 'Codex wrapper should not duplicate modal markup')
assert.ok(!nousModal.includes('<Modal'), 'Nous wrapper should not duplicate modal markup')
