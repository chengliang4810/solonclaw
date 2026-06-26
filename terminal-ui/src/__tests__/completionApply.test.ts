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
})
