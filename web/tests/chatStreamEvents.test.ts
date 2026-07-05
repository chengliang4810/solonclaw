import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const testsDir = dirname(fileURLToPath(import.meta.url))
const tempDir = mkdtempSync(join(testsDir, '.tmp-chat-stream-events-'))

try {
  const chatPath = join(tempDir, 'chat-under-test.ts')
  const source = readFileSync(new URL('../src/api/solonclaw/chat.ts', import.meta.url), 'utf8')
    .replace("import { dashboardFetch, getApiKey, getBaseUrlValue, request } from '../client'", "import { dashboardFetch, getApiKey, getBaseUrlValue, request } from './mock-client.ts'")

  writeFileSync(join(tempDir, 'mock-client.ts'), `
export function getApiKey() { return '' }
export function getBaseUrlValue() { return 'http://dashboard.local' }
export function request() { throw new Error('request should not be called') }
export async function dashboardFetch() {
  const encoder = new TextEncoder()
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(encoder.encode('event: message\\ndata: not-json\\n\\n'))
      controller.enqueue(encoder.encode('event: message\\ndata: {"delta":"ok"}\\n\\n'))
      controller.close()
    },
  }), { status: 200 })
}
`)
  writeFileSync(chatPath, source)

  const { streamRunEvents } = await import(pathToFileURL(chatPath).href)
  const events: Array<{ event: string; delta?: string }> = []
  const errors: Error[] = []

  await new Promise<void>((resolve, reject) => {
    streamRunEvents(
      'run-1',
      event => events.push(event),
      resolve,
      error => {
        errors.push(error)
        reject(error)
      },
    )
  })

  assert.deepEqual(errors, [], 'Malformed SSE data frames should not stop the stream')
  assert.deepEqual(events, [{ event: 'message', delta: 'ok' }])
} finally {
  rmSync(tempDir, { recursive: true, force: true })
}
