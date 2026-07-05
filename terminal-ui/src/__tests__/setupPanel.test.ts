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
      { description: '提供方、API Key、模型', key: 'model', label: '模型' },
      { description: '国内消息渠道', key: 'gateway', label: '渠道' },
      { description: '检查模型与渠道配置', key: 'doctor', label: '诊断' }
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
        workspace_config: '/tmp/workspace/config.yml'
      })
    ).toEqual(['模型：已配置', '提供方：openai', '当前模型：mimo-v2.5-pro', '配置文件：/tmp/workspace/config.yml'])
  })

  it('formats missing setup status without hiding the next action', () => {
    expect(setupStatusLines({ provider_configured: false })).toEqual([
      '模型：未配置',
      '提供方：（未设置）',
      '当前模型：（未设置）',
      '配置文件：（未知）'
    ])
  })

  it('formats setup warning when backend detects a blocked local provider URL', () => {
    expect(
      setupStatusLines({
        model: 'mimo-v2.5',
        provider: 'openai',
        provider_configured: true,
        warning: '模型地址被安全策略阻断：阻断内网/私有地址；设置 security.allowPrivateUrls=true 后重试。',
        workspace_config: '/tmp/workspace/config.yml'
      })
    ).toContain('警告：模型地址被安全策略阻断：阻断内网/私有地址；设置 security.allowPrivateUrls=true 后重试。')
  })
})
