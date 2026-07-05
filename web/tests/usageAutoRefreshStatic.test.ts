import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const view = readFileSync(new URL('../src/views/solonclaw/UsageView.vue', import.meta.url), 'utf8')

assert.ok(view.includes('onUnmounted'), 'UsageView should clean up its refresh timer')
assert.ok(view.includes('const refreshTimer = ref<ReturnType<typeof setInterval> | null>(null)'), 'UsageView should keep the refresh timer handle')
assert.ok(view.includes('setInterval(loadUsage, 30000)'), 'UsageView should refresh usage data periodically')
assert.ok(view.includes('clearInterval(refreshTimer.value)'), 'UsageView should clear the refresh timer on unmount')
