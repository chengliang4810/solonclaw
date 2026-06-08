import { describe, expect, it } from 'vitest'

import {
  clampSetupActionIndex,
  createSetupPanelNavigator,
  setupActionAt,
  setupPanelRows,
  setupStatusLines
} from '../components/setupPanel.js'

describe('setup panel helpers', () => {
  it('lists model, channel, and doctor setup actions in user workflow order', () => {
    expect(setupPanelRows()).toEqual([
      { description: 'Provider, API key, model', key: 'model', label: 'Model' },
      { description: 'Domestic messaging channels', key: 'gateway', label: 'Channels' },
      { description: 'Run model and channel checks', key: 'doctor', label: 'Doctor' }
    ])
  })

  it('clamps keyboard selection to available setup actions', () => {
    expect(clampSetupActionIndex(-1)).toBe(0)
    expect(clampSetupActionIndex(1)).toBe(1)
    expect(clampSetupActionIndex(99)).toBe(2)
  })

  it('maps selected rows to setup actions', () => {
    expect(setupActionAt(0)).toBe('model')
    expect(setupActionAt(1)).toBe('gateway')
    expect(setupActionAt(2)).toBe('doctor')
    expect(setupActionAt(99)).toBe('doctor')
  })

  it('opens the latest selected action when navigation and enter arrive in one input burst', () => {
    const navigator = createSetupPanelNavigator()

    expect(navigator.move(1)).toBe(1)
    expect(navigator.open()).toBe('gateway')
    expect(navigator.move(1)).toBe(2)
    expect(navigator.open()).toBe('doctor')
  })

  it('formats configured setup status with provider, model, and config path', () => {
    expect(
      setupStatusLines({
        model: 'mimo-v2.5-pro',
        provider: 'openai',
        provider_configured: true,
        runtime_config: '/tmp/runtime/config.yml'
      })
    ).toEqual(['model: configured', 'provider: openai', 'current: mimo-v2.5-pro', 'config: /tmp/runtime/config.yml'])
  })

  it('formats missing setup status without hiding the next action', () => {
    expect(setupStatusLines({ provider_configured: false })).toEqual([
      'model: missing',
      'provider: (unset)',
      'current: (unset)',
      'config: (unknown)'
    ])
  })
})
