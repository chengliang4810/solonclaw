import { describe, expect, it } from 'vitest'

import { applyCompletion, completionToApplyOnSubmit } from '../domain/slash.js'

describe('applyCompletion', () => {
  it('replaces from compReplace and drops duplicate leading slash', () => {
    expect(applyCompletion('/ex', 'exit', 1)).toBe('/exit')
    expect(applyCompletion('/ex', '/exit', 1)).toBe('/exit')
  })

  it('preserves a leading slash when slash completions replace the whole command token', () => {
    expect(applyCompletion('/n', '/new', 0)).toBe('/new')
  })

  it('replaces an argument token after a space', () => {
    expect(applyCompletion('/cron ad', 'add', 6)).toBe('/cron add')
  })
})

describe('completionToApplyOnSubmit', () => {
  it('accepts a real token completion', () => {
    expect(completionToApplyOnSubmit('/ex', 'exit', 1)).toBe('/exit')
    expect(completionToApplyOnSubmit('/cron ad', 'add', 6)).toBe('/cron add')
    expect(completionToApplyOnSubmit('/n', '/new', 0)).toBe('/new')
  })

  it('does not swallow Enter for trailing-space-only completions', () => {
    expect(completionToApplyOnSubmit('/exit', 'exit ', 1)).toBeNull()
    expect(completionToApplyOnSubmit('/cron add', 'add ', 6)).toBeNull()
  })

  it('does not swallow Enter for empty or no-op completions', () => {
    expect(completionToApplyOnSubmit('/new', '/new', 0)).toBeNull()
    expect(completionToApplyOnSubmit('/exit', undefined, 1)).toBeNull()
    expect(completionToApplyOnSubmit('/exit', '', 1)).toBeNull()
    expect(completionToApplyOnSubmit('/exit', 'exit', 1)).toBeNull()
  })

  it('does not replace an exact slash command with a different completion on Enter', () => {
    expect(completionToApplyOnSubmit('/config check', '/commands', 0)).toBeNull()
    expect(completionToApplyOnSubmit('/status', '/sessions', 0)).toBeNull()
  })

  it('submits an exact slash command even when a longer command is selected', () => {
    expect(completionToApplyOnSubmit('/status', '/statusbar', 0, ['/status', '/statusbar'], true)).toBeNull()
    expect(completionToApplyOnSubmit('/reload', '/reload-skills', 0, ['/reload-skills', '/reload-mcp'], true)).toBeNull()
  })

  it('still applies longer slash completion when the current value is not an exact command', () => {
    expect(completionToApplyOnSubmit('/reloa', '/reload', 0, ['/reload'], false)).toBe('/reload')
  })
})
