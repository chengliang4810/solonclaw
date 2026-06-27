import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/media.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/ChannelsView.vue', import.meta.url), 'utf8')

for (const endpoint of ['/api/media/${encodeURIComponent(mediaId)}', '/refresh', '/download', '/reference']) {
  assert.ok(api.includes(endpoint), `${endpoint} should be wrapped by the frontend media API client`)
}

for (const symbol of ['refreshMedia', 'downloadMedia', 'referenceMedia']) {
  assert.ok(view.includes(symbol), `${symbol} should be reachable from the Channels UI`)
}
