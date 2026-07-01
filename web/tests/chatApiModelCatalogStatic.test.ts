import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const chatApiFile = new URL('../src/api/solonclaw/chat.ts', import.meta.url)
const systemApiFile = new URL('../src/api/solonclaw/system.ts', import.meta.url)

const chatApi = readFileSync(chatApiFile, 'utf8')
const systemApi = readFileSync(systemApiFile, 'utf8')

assert.ok(
  !chatApi.includes('fetchModels()'),
  'chat API should not expose an empty model catalog stub',
)
assert.ok(
  systemApi.includes('fetchAvailableModels'),
  'model catalog loading should stay in the system API provider catalog path',
)
assert.ok(
  systemApi.includes('fetchRuntimeModels'),
  'runtime model status loading should stay in the system API model status path',
)
