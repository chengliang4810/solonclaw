import { describe, expect, it } from 'vitest'

import { looksLikeLocalCliCommand } from '../domain/localCommands.js'

describe('looksLikeLocalCliCommand', () => {
  it('routes bare local setup commands away from chat submission', () => {
    expect(looksLikeLocalCliCommand('model set --provider local --model mimo-v2.5')).toBe(true)
    expect(looksLikeLocalCliCommand('auth add local --api-key secret')).toBe(true)
    expect(looksLikeLocalCliCommand('doctor')).toBe(true)
    expect(looksLikeLocalCliCommand('config path')).toBe(true)
    expect(looksLikeLocalCliCommand('status')).toBe(true)
    expect(looksLikeLocalCliCommand('setup')).toBe(true)
    expect(looksLikeLocalCliCommand('setup model')).toBe(true)
    expect(looksLikeLocalCliCommand('setup gateway')).toBe(true)
    expect(looksLikeLocalCliCommand('setup --quick')).toBe(true)
  })

  it('leaves natural language model text as chat', () => {
    expect(looksLikeLocalCliCommand('model a safer workflow for this app')).toBe(false)
    expect(looksLikeLocalCliCommand('status of my plan')).toBe(false)
    expect(looksLikeLocalCliCommand('setup a deployment plan')).toBe(false)
  })
})
