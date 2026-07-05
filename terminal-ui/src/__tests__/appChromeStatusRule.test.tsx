import React from 'react'
import { describe, expect, it, vi } from 'vitest'

import { StatusRule } from '../components/appChrome.js'
import { DEFAULT_THEME } from '../theme.js'

type ReactNodeLike = React.ReactNode

const textContent = (node: ReactNodeLike): string => {
  if (node === null || node === undefined || typeof node === 'boolean') {
    return ''
  }

  if (typeof node === 'string' || typeof node === 'number') {
    return String(node)
  }

  if (Array.isArray(node)) {
    return node.map(textContent).join('')
  }

  if (React.isValidElement(node)) {
    return textContent(node.props.children)
  }

  return ''
}

const findClickableWithText = (node: ReactNodeLike, needle: string): React.ReactElement | null => {
  if (node === null || node === undefined || typeof node === 'boolean') {
    return null
  }

  if (Array.isArray(node)) {
    for (const child of node) {
      const found = findClickableWithText(child, needle)

      if (found) {
        return found
      }
    }

    return null
  }

  if (!React.isValidElement(node)) {
    return null
  }

  if (typeof node.props.onClick === 'function' && textContent(node).includes(needle)) {
    return node
  }

  return findClickableWithText(node.props.children, needle)
}

const findComponentByName = (node: ReactNodeLike, name: string): React.ReactElement | null => {
  if (node === null || node === undefined || typeof node === 'boolean') {
    return null
  }

  if (Array.isArray(node)) {
    for (const child of node) {
      const found = findComponentByName(child, name)

      if (found) {
        return found
      }
    }

    return null
  }

  if (!React.isValidElement(node)) {
    return null
  }

  if (typeof node.type === 'function' && node.type.name === name) {
    return node
  }

  return findComponentByName(node.props.children, name)
}

describe('StatusRule session count click target', () => {
  it('makes the live session count itself clickable', () => {
    const openSwitcher = vi.fn()

    const element = StatusRule({
      bgCount: 0,
      busy: false,
      cols: 100,
      cwdLabel: '~/repo',
      liveSessionCount: 1,
      model: 'kimi-k2.6',
      onSessionCountClick: openSwitcher,
      sessionStartedAt: null,
      showCost: false,
      status: 'ready',
      statusColor: DEFAULT_THEME.color.ok,
      t: DEFAULT_THEME,
      turnStartedAt: null,
      usage: { total: 0 },
      voiceLabel: ''
    })

    const clickableSessionCount = findClickableWithText(element, '1 session')

    expect(clickableSessionCount).not.toBeNull()
    clickableSessionCount!.props.onClick({ stopImmediatePropagation: vi.fn() })
    expect(openSwitcher).toHaveBeenCalledOnce()
  })

  it('keeps status + model and drops the low-value tail on a narrow terminal', () => {
    const element = StatusRule({
      bgCount: 0,
      busy: false,
      cols: 44,
      cwdLabel: '~/src/solonclaw-agent/apps/desktop (bb/tui-statusbar-responsive)',
      liveSessionCount: 3,
      model: 'opus-4.8',
      onSessionCountClick: vi.fn(),
      sessionStartedAt: Date.now() - 60_000,
      showCost: true,
      status: 'ready',
      statusColor: DEFAULT_THEME.color.ok,
      t: DEFAULT_THEME,
      turnStartedAt: null,
      usage: { context_max: 200_000, context_percent: 25, context_used: 50_000, cost_usd: 0.5, total: 50_000 },
      voiceLabel: 'voice off'
    })

    const rendered = textContent(element)

    // Must-keep essentials survive intact …
    expect(rendered).toContain('ready')
    expect(rendered).toContain('opus 4.8')
    // … while the low-value tail (session count, cost) is dropped, not truncated.
    expect(rendered).not.toContain('3 sessions')
    expect(rendered).not.toContain('$0.5000')
  })

  it('shows active subagents from usage when no local background task is tracked', () => {
    const element = StatusRule({
      bgCount: 0,
      busy: false,
      cols: 100,
      cwdLabel: '~/repo',
      liveSessionCount: 0,
      model: 'kimi-k2.6',
      sessionStartedAt: null,
      showCost: false,
      status: 'ready',
      statusColor: DEFAULT_THEME.color.ok,
      t: DEFAULT_THEME,
      turnStartedAt: null,
      usage: { active_subagents: 2, total: 0 },
      voiceLabel: ''
    })

    expect(textContent(element)).toContain('2 bg')
  })
})

describe('StatusRule idle-since readout', () => {
  it('shows time since the last final response while idle', () => {
    const endedAt = Date.now() - 42_000

    const element = StatusRule({
      bgCount: 0,
      busy: false,
      cols: 120,
      cwdLabel: '~/repo',
      lastTurnEndedAt: endedAt,
      liveSessionCount: 0,
      model: 'kimi-k2.6',
      sessionStartedAt: null,
      showCost: false,
      status: 'ready',
      statusColor: DEFAULT_THEME.color.ok,
      t: DEFAULT_THEME,
      turnStartedAt: null,
      usage: { total: 0 },
      voiceLabel: ''
    })

    const idle = findComponentByName(element, 'IdleSince')

    expect(idle).not.toBeNull()
    expect(idle!.props.endedAt).toBe(endedAt)
  })

  it('hides idle-since while a turn is busy', () => {
    const element = StatusRule({
      bgCount: 0,
      busy: true,
      cols: 120,
      cwdLabel: '~/repo',
      lastTurnEndedAt: Date.now() - 42_000,
      liveSessionCount: 0,
      model: 'kimi-k2.6',
      sessionStartedAt: null,
      showCost: false,
      status: 'running…',
      statusColor: DEFAULT_THEME.color.ok,
      t: DEFAULT_THEME,
      turnStartedAt: Date.now(),
      usage: { total: 0 },
      voiceLabel: ''
    })

    expect(findComponentByName(element, 'IdleSince')).toBeNull()
  })
})
