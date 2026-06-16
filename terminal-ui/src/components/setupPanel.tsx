import { Box, Text, useInput } from '@solon-claw/ink'
import { useEffect, useRef, useState } from 'react'

import type { GatewayClient } from '../gatewayClient.js'
import type { SetupStatusResponse } from '../gatewayTypes.js'
import { asRpcResult, rpcErrorMessage } from '../lib/rpc.js'
import type { Theme } from '../theme.js'

import { OverlayHint, useOverlayKeys } from './overlayControls.js'

const SETUP_ACTIONS = [
  {
    description: '提供方、API Key、模型',
    key: 'model',
    label: '模型'
  },
  {
    description: '国内消息渠道',
    key: 'gateway',
    label: '渠道'
  },
  {
    description: '检查模型与渠道配置',
    key: 'doctor',
    label: '诊断'
  }
] as const

export type SetupActionKey = (typeof SETUP_ACTIONS)[number]['key']

export const setupPanelRows = () => SETUP_ACTIONS.map(action => ({ ...action }))

export const clampSetupActionIndex = (index: number) =>
  Math.max(0, Math.min(index, SETUP_ACTIONS.length - 1))

export const setupActionAt = (index: number): SetupActionKey => SETUP_ACTIONS[clampSetupActionIndex(index)]!.key

export const createSetupPanelNavigator = (initialIndex = 0) => {
  let currentIndex = clampSetupActionIndex(initialIndex)

  return {
    current: () => currentIndex,
    move: (delta: number) => {
      currentIndex = clampSetupActionIndex(currentIndex + delta)

      return currentIndex
    },
    open: () => setupActionAt(currentIndex)
  }
}

export const setupStatusLines = (status: SetupStatusResponse | null) => [
  `模型：${status?.provider_configured ? '已配置' : '未配置'}`,
  `提供方：${status?.provider || '（未设置）'}`,
  `当前模型：${status?.model || '（未设置）'}`,
  `配置文件：${status?.runtime_config || '（未知）'}`
]

export function SetupPanel({ gw, onChannel, onClose, onDoctor, onModel, t }: SetupPanelProps) {
  const [err, setErr] = useState('')
  const navigatorRef = useRef(createSetupPanelNavigator())
  const [idx, setIdx] = useState(navigatorRef.current.current())
  const [status, setStatus] = useState<SetupStatusResponse | null>(null)

  useEffect(() => {
    gw.request<SetupStatusResponse>('setup.status', {})
      .then(raw => {
        const result = asRpcResult<SetupStatusResponse>(raw)

        if (result) {
          setStatus(result)
        }
      })
      .catch((e: unknown) => setErr(rpcErrorMessage(e)))
  }, [gw])

  useOverlayKeys({ disabled: true, onClose })

  useInput((ch, key) => {
    if (key.escape || ch === 'q') {
      onClose()

      return
    }

    if (key.upArrow) {
      setIdx(navigatorRef.current.move(-1))

      return
    }

    if (key.downArrow) {
      setIdx(navigatorRef.current.move(1))

      return
    }

    if (!key.return) {
      return
    }

    const action = navigatorRef.current.open()

    if (action === 'model') {
      onModel()
    } else if (action === 'gateway') {
      onChannel()
    } else if (action === 'doctor') {
      onDoctor()
    }
  })

  return (
    <Box flexDirection="column" width={64}>
      <Text bold color={t.color.accent} wrap="truncate-end">
        设置
      </Text>

      <Text color={t.color.muted} wrap="truncate-end">
        模型、渠道与运行时检查
      </Text>

      {setupStatusLines(status).map((line, lineIdx) => (
        <Text color={lineIdx === 0 && status?.provider_configured ? t.color.ok : lineIdx === 0 ? t.color.warn : t.color.muted} key={line} wrap="truncate-end">
          {line}
        </Text>
      ))}

      {err ? (
        <Text color={t.color.label} wrap="truncate-end">
          错误：{err}
        </Text>
      ) : (
        <Text color={t.color.muted} wrap="truncate-end">
          {' '}
        </Text>
      )}

      {SETUP_ACTIONS.map((item, actionIdx) => (
        <Text
          bold={idx === actionIdx}
          color={idx === actionIdx ? t.color.accent : t.color.muted}
          inverse={idx === actionIdx}
          key={item.key}
          wrap="truncate-end"
        >
          {idx === actionIdx ? '▸ ' : '  '}
          {item.label} · {item.description}
        </Text>
      ))}

      <Text color={t.color.muted} wrap="truncate-end">
        {' '}
      </Text>

      <OverlayHint t={t}>↑/↓ 选择 · Enter 打开 · Esc/q 关闭</OverlayHint>
    </Box>
  )
}

interface SetupPanelProps {
  gw: GatewayClient
  onChannel: () => void
  onClose: () => void
  onDoctor: () => void
  onModel: () => void
  t: Theme
}
