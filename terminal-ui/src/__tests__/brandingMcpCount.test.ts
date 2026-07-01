import { PassThrough } from 'node:stream'

import { renderSync } from '@solonclaw/ink'
import React from 'react'
import { describe, expect, it } from 'vitest'

import { collapseToggleMeta, connectedMcpServerCount, mcpHeadlineSuffix, SessionPanel } from '../components/branding.js'
import { stripAnsi } from '../lib/text.js'
import { DEFAULT_THEME } from '../theme.js'
import type { McpServerStatus, SessionInfo } from '../types.js'

const mcp = (overrides: Partial<McpServerStatus> & Pick<McpServerStatus, 'name'>): McpServerStatus => ({
  connected: false,
  tools: 0,
  transport: 'http',
  ...overrides
})

const info = (mcp_servers: McpServerStatus[]): SessionInfo => ({
  mcp_servers,
  model: 'test-model',
  skills: { core: ['skill-a'] },
  tools: { file: ['read_file'] }
})

const renderPlain = (sessionInfo: SessionInfo) => {
  const stdout = new PassThrough()
  const stdin = new PassThrough()
  const stderr = new PassThrough()
  let output = ''

  Object.assign(stdout, { columns: 100, isTTY: false, rows: 30 })
  Object.assign(stdin, { isTTY: false })
  Object.assign(stderr, { isTTY: false })
  stdout.on('data', chunk => {
    output += chunk.toString()
  })

  const instance = renderSync(React.createElement(SessionPanel, { info: sessionInfo, sid: 'sid', t: DEFAULT_THEME }), {
    patchConsole: false,
    stderr: stderr as NodeJS.WriteStream,
    stdin: stdin as NodeJS.ReadStream,
    stdout: stdout as NodeJS.WriteStream
  })

  instance.unmount()
  instance.cleanup()

  return stripAnsi(output)
}

describe('SessionPanel MCP headline count', () => {
  it('formats collapsible heading metadata without changing layout state', () => {
    expect(collapseToggleMeta()).toBe('')
    expect(collapseToggleMeta(0)).toBe(' (0)')
    expect(collapseToggleMeta(12, 'connected')).toBe(' (12) connected')
    expect(collapseToggleMeta(undefined, '— 42 chars')).toBe(' — 42 chars')
  })

  it('formats connected MCP headline suffix from connected server count only', () => {
    const servers = [
      mcp({ connected: true, name: 'connected', status: 'connected', tools: 2 }),
      mcp({ connected: false, disabled: true, name: 'disabled', status: 'disabled' })
    ]

    expect(connectedMcpServerCount(servers)).toBe(1)
    expect(mcpHeadlineSuffix(1)).toBe(' · 1 MCP')
    expect(mcpHeadlineSuffix(0)).toBe('')
  })

  it('counts connected MCP servers rather than every configured server', () => {
    const output = renderPlain(
      info([
        mcp({ connected: true, name: 'connected', status: 'connected', tools: 2 }),
        mcp({ connected: false, disabled: true, name: 'disabled', status: 'disabled' })
      ])
    )

    expect(output).toContain('1 MCP')
    expect(output).not.toContain('2 MCP')
  })

  it('hides the headline MCP segment when no server is connected', () => {
    const output = renderPlain(info([mcp({ connected: false, disabled: true, name: 'disabled', status: 'disabled' })]))

    expect(output).not.toMatch(/\d MCP\b/)
  })
})
