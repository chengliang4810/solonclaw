import { describe, expect, it } from 'vitest'

import { findSlashCommand, SLASH_COMMANDS } from '../app/slash/registry.js'

type CommandRoute = 'fallback' | 'local' | 'native'

const NATIVE_MUTATING_COMMANDS = new Set(['browser', 'busy', 'fast', 'reload-mcp', 'rollback', 'stop'])

const MUTATING_COMMANDS = [
  'background',
  'branch',
  'browser',
  'busy',
  'clear',
  'compress',
  'fast',
  'model',
  'new',
  'queue',
  'reasoning',
  'reload-mcp',
  'retry',
  'rollback',
  'steer',
  'stop',
  'title',
  'tools',
  'undo',
  'verbose',
  'voice',
  'yolo'
] as const

const LOCAL_COMMAND_NAMES = new Set(
  SLASH_COMMANDS.flatMap(command => [command.name, ...(command.aliases ?? [])].map(name => name.toLowerCase()))
)

const COMMAND_REGISTRY_NAMES = [...LOCAL_COMMAND_NAMES]

const classifyRoute = (name: string): CommandRoute => {
  const normalized = name.toLowerCase()

  if (NATIVE_MUTATING_COMMANDS.has(normalized)) {
    return 'native'
  }

  if (LOCAL_COMMAND_NAMES.has(normalized)) {
    return 'local'
  }

  return 'fallback'
}

describe('slash parity matrix', () => {
  it('classifies each TUI command registry command as local/native/fallback', () => {
    const routes = Object.fromEntries(COMMAND_REGISTRY_NAMES.map(name => [name, classifyRoute(name)]))

    expect(routes['model']).toBe('local')
    expect(routes['browser']).toBe('native')
    expect(routes['reload-mcp']).toBe('native')
    expect(routes['rollback']).toBe('native')
    expect(routes['stop']).toBe('native')
  })

  it('keeps every mutating command off slash-worker fallback', () => {
    const routes = Object.fromEntries(COMMAND_REGISTRY_NAMES.map(name => [name, classifyRoute(name)]))

    for (const name of MUTATING_COMMANDS) {
      expect(routes[name], `missing command in registry: ${name}`).toBeDefined()
      expect(routes[name], `mutating command must not fallback: ${name}`).not.toBe('fallback')
    }
  })

  it('/q alias resolves to queue, not quit (#31983)', () => {
    // Regression for #31983: the TUI `quit` command used to carry alias `q`,
    // which collided with the Python-side `/queue` alias. TUI-local commands
    // dispatch before the backend, so `/q` resolved to /quit (session.die)
    // instead of queueing a prompt.
    const cmd = findSlashCommand('q')
    expect(cmd, '/q must resolve to a command').toBeDefined()
    expect(cmd!.name).toBe('queue')
  })

  it('does not expose removed commands', () => {
    for (const name of ['fortune', 'skin', 'update']) {
      expect(findSlashCommand(name)).toBeUndefined()
    }
  })
})
