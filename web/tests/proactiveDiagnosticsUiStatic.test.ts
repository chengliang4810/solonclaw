import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/diagnostics.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url), 'utf8')

assert.ok(api.includes('proactive?:'), 'diagnostics API should expose reminder status')
assert.ok(view.includes('topic_cooldown_hours'), 'diagnostics should show topic cooldown')
assert.ok(view.includes('main_conversation_ready'), 'diagnostics should show main conversation readiness')
assert.ok(view.includes('last_outcome'), 'diagnostics should show the last scheduler outcome')
assert.ok(view.includes('last_reason'), 'diagnostics should show why the last check sent or skipped')
assert.ok(view.includes('analysis_reason'), 'diagnostics should show the persisted activity analysis reason')
assert.ok(view.includes('activity_credit'), 'diagnostics should show accumulated delivery credit')
assert.ok(!api.includes('/api/diagnostics/proactive/retry'), 'removed candidate retry API must stay absent')
