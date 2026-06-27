import assert from 'node:assert/strict'
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

const srcRoot = new URL('../src', import.meta.url)
const apiFile = new URL('../src/api/solonclaw/conversations.ts', import.meta.url)
const paneFile = new URL('../src/components/solonclaw/chat/ConversationMonitorPane.vue', import.meta.url)

assert.equal(existsSync(apiFile), false, 'unused conversations API stub should not stay in the bundle')
assert.equal(existsSync(paneFile), false, 'unused conversation monitor pane should not stay in the bundle')

function listFiles(dir: string): string[] {
  return readdirSync(dir).flatMap(name => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return listFiles(path)
    return [path]
  })
}

const references = listFiles(srcRoot.pathname)
  .filter(path => /\.(ts|vue)$/.test(path))
  .filter(path => !path.endsWith('/i18n/locales/en.ts') && !path.endsWith('/i18n/locales/zh.ts'))
  .filter(path => readFileSync(path, 'utf8').includes('ConversationMonitorPane'))

assert.deepEqual(references, [], 'no source file should reference the removed conversation monitor pane')
