import { appendFileSync, mkdirSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

// Mirror the backend bridge crash log from the Node parent so lifecycle
// breadcrumbs interleave, by timestamp, with transport exit diagnostics.
//
// A transport exit is usually a parent action: `gw.kill()` during graceful exit
// or `start()` replacing a live socket. It can also come from an external
// supervisor or network failure. Persisting the death-explaining parent events
// here keeps that context available after the terminal process exits.
const logDir = join(process.env.SOLONCLAW_HOME?.trim() || join(homedir(), '.solonclaw'), 'logs')
const CRASH_LOG = join(logDir, 'terminal-ui-bridge.log')

// Skipped under vitest so unit tests exercising start()/kill() can't write into
// a real ~/.solonclaw (tests must stay hermetic — see AGENTS.md).
const enabled = !process.env.VITEST
// Slice a single breadcrumb's value to MAX_BREADCRUMB chars (a short
// "[truncated …]" marker is appended, so the written line is slightly longer)
// so a pathological value (e.g. a giant error) can't bloat the shared crash log
// or add noticeable blocking on the synchronous append. Mirrors the spirit of
// GatewayClient's in-memory log-line cap.
const MAX_BREADCRUMB = 4096
let warned = false

export function recordParentLifecycle(line: string): void {
  if (!enabled) {
    return
  }

  try {
    // Collapse embedded newlines so a multi-line value (e.g. an error message)
    // stays one breadcrumb and can't masquerade as a separate log entry or as
    // the child's panic output sharing this file.
    const oneLine = line.replace(/[\r\n]+/g, ' ↵ ')

    const capped =
      oneLine.length > MAX_BREADCRUMB ? `${oneLine.slice(0, MAX_BREADCRUMB)}… [truncated ${oneLine.length} chars]` : oneLine

    mkdirSync(logDir, { recursive: true })
    appendFileSync(CRASH_LOG, `[tui-parent] ${new Date().toISOString()} ${capped}\n`)
  } catch {
    if (!warned) {
      warned = true
      process.stderr.write('solonclaw-tui: parent lifecycle log unavailable\n')
    }
  }
}
