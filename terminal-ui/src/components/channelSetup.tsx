import { Box, Text, useInput, useStdout } from '@solonclaw/ink'
import { useEffect, useMemo, useState } from 'react'

import type { GatewayClient } from '../gatewayClient.js'
import type { ChannelOption, ChannelOptionsResponse, ChannelSaveResponse } from '../gatewayTypes.js'
import { asRpcResult, rpcErrorMessage } from '../lib/rpc.js'
import type { Theme } from '../theme.js'

import { OverlayHint, useOverlayKeys, windowItems } from './overlayControls.js'

const VISIBLE = 10
const MIN_WIDTH = 44
const MAX_WIDTH = 90

type Stage = 'channel' | 'fields' | 'saved'

export function ChannelSetup({ gw, onClose, sessionId, t }: ChannelSetupProps) {
  const [channels, setChannels] = useState<ChannelOption[]>([])
  const [channelIdx, setChannelIdx] = useState(0)
  const [err, setErr] = useState('')
  const [fieldIdx, setFieldIdx] = useState(0)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [stage, setStage] = useState<Stage>('channel')
  const [values, setValues] = useState<Record<string, string>>({})

  const { stdout } = useStdout()
  const width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, (stdout?.columns ?? 80) - 6))

  useEffect(() => {
    gw.request<ChannelOptionsResponse>('channel.options', sessionId ? { session_id: sessionId } : {})
      .then(raw => {
        const r = asRpcResult<ChannelOptionsResponse>(raw)

        if (!r) {
          setErr('invalid response: channel.options')
          setLoading(false)

          return
        }

        setChannels(r.channels ?? [])
        setErr('')
        setLoading(false)
      })
      .catch((e: unknown) => {
        setErr(rpcErrorMessage(e))
        setLoading(false)
      })
  }, [gw, sessionId])

  const channel = channels[channelIdx]
  const fields = useMemo(() => channel?.fields ?? [], [channel])
  const field = fields[fieldIdx]

  const back = () => {
    if (stage === 'saved') {
      setStage('channel')
      setValues({})
      setFieldIdx(0)

      return
    }

    if (stage === 'fields') {
      if (fieldIdx > 0) {
        setFieldIdx(v => v - 1)

        return
      }

      setStage('channel')
      setValues({})

      return
    }

    onClose()
  }

  useOverlayKeys({ disabled: stage === 'channel' || stage === 'fields', onBack: back, onClose })

  const save = (overrideValues?: Record<string, string>) => {
    if (!channel || saving) {
      return
    }

    const nextValues = overrideValues ?? values
    setSaving(true)
    setErr('')
    gw.request<ChannelSaveResponse>('channel.save', {
      channel: channel.key,
      values: nextValues,
      ...(sessionId ? { session_id: sessionId } : {})
    })
      .then(raw => {
        const r = asRpcResult<ChannelSaveResponse>(raw)

        if (!r?.saved) {
          setErr('failed to save channel')
          setSaving(false)

          return
        }

        setSaving(false)
        setStage('saved')
      })
      .catch((e: unknown) => {
        setErr(rpcErrorMessage(e))
        setSaving(false)
      })
  }

  useInput((ch, key) => {
    if (loading || saving) {
      return
    }

    if (stage === 'channel') {
      if (key.escape) {
        onClose()

        return
      }

      if (ch === 'q') {
        onClose()

        return
      }

      if (key.upArrow && channelIdx > 0) {
        setChannelIdx(v => v - 1)

        return
      }

      if (key.downArrow && channelIdx < channels.length - 1) {
        setChannelIdx(v => v + 1)

        return
      }

      if (key.return && channel) {
        const initialValues = { enabled: 'true' }
        setValues(initialValues)
        setFieldIdx(0)
        fields.length ? setStage('fields') : save(initialValues)

        return
      }

      return
    }

    if (stage === 'saved') {
      if (key.escape || key.return || ch === 'q') {
        setStage('channel')
        setValues({})
        setFieldIdx(0)
      }

      return
    }

    if (!field) {
      return
    }

    if (key.escape) {
      back()

      return
    }

    if (key.return) {
      if (fieldIdx < fields.length - 1) {
        setFieldIdx(v => v + 1)

        return
      }

      save()

      return
    }

    if (key.backspace || key.delete) {
      setValues(prev => ({ ...prev, [field.key]: (prev[field.key] ?? '').slice(0, -1) }))

      return
    }

    if (key.ctrl && ch === 'u') {
      setValues(prev => ({ ...prev, [field.key]: '' }))

      return
    }

    if (ch && !key.ctrl && !key.meta && ch.length === 1 && ch >= ' ') {
      setValues(prev => ({ ...prev, [field.key]: (prev[field.key] ?? '') + ch }))
    }
  })

  if (loading) {
    return <Text color={t.color.muted}>loading channels…</Text>
  }

  if (err && stage === 'channel') {
    return (
      <Box flexDirection="column" width={width}>
        <Text color={t.color.label}>error: {err}</Text>
        <OverlayHint t={t}>Esc/q cancel</OverlayHint>
      </Box>
    )
  }

  if (!channels.length) {
    return (
      <Box flexDirection="column" width={width}>
        <Text color={t.color.muted}>no channels available</Text>
        <OverlayHint t={t}>Esc/q cancel</OverlayHint>
      </Box>
    )
  }

  if (stage === 'fields' && channel && field) {
    const value = values[field.key] ?? ''
    const shown = field.secret && value ? '•'.repeat(Math.min(value.length, 40)) : value

    return (
      <Box flexDirection="column" width={width}>
        <Text bold color={t.color.accent} wrap="truncate-end">
          Configure {channel.label}
        </Text>
        <Text color={t.color.muted} wrap="truncate-end">
          {fieldIdx + 1}/{fields.length} · {field.label || field.key}
        </Text>
        <Text color={t.color.muted} wrap="truncate-end">
          {field.description || ' '}
        </Text>
        <Text color={t.color.accent} wrap="truncate-end">
          {shown || '(empty)'}
          {saving ? '' : '▎'}
        </Text>
        <Text color={err ? t.color.label : t.color.muted} wrap="truncate-end">
          {err ? `error: ${err}` : saving ? 'saving…' : ' '}
        </Text>
        <OverlayHint t={t}>Enter next/save · Ctrl+U clear · Esc back</OverlayHint>
      </Box>
    )
  }

  if (stage === 'saved' && channel) {
    return (
      <Box flexDirection="column" width={width}>
        <Text bold color={t.color.accent} wrap="truncate-end">
          {channel.label} saved
        </Text>
        <Text color={t.color.muted} wrap="truncate-end">
          You can start or refresh the backend gateway after updating channel credentials.
        </Text>
        <OverlayHint t={t}>Enter/Esc back · q close</OverlayHint>
      </Box>
    )
  }

  const rows = channels.map(c => {
    const status = c.configured ? 'configured' : 'not configured'

    return `${c.label} · ${status}`
  })

  const { items, offset } = windowItems(rows, channelIdx, VISIBLE)

  return (
    <Box flexDirection="column" width={width}>
      <Text bold color={t.color.accent} wrap="truncate-end">
        Channel setup
      </Text>
      <Text color={t.color.muted} wrap="truncate-end">
        Domestic channels only · Enter configure
      </Text>
      <Text color={t.color.muted} wrap="truncate-end">
        {offset > 0 ? ` ↑ ${offset} more` : ' '}
      </Text>
      {Array.from({ length: VISIBLE }, (_, i) => {
        const row = items[i]
        const idx = offset + i

        return row ? (
          <Text
            bold={channelIdx === idx}
            color={channelIdx === idx ? t.color.accent : t.color.muted}
            inverse={channelIdx === idx}
            key={channels[idx]?.key ?? `row-${idx}`}
            wrap="truncate-end"
          >
            {channelIdx === idx ? '▸ ' : '  '}
            {idx + 1}. {row}
          </Text>
        ) : (
          <Text color={t.color.muted} key={`pad-${i}`} wrap="truncate-end">
            {' '}
          </Text>
        )
      })}
      <Text color={t.color.muted} wrap="truncate-end">
        {offset + VISIBLE < rows.length ? ` ↓ ${rows.length - offset - VISIBLE} more` : ' '}
      </Text>
      <OverlayHint t={t}>↑/↓ select · Enter configure · Esc/q close</OverlayHint>
    </Box>
  )
}

interface ChannelSetupProps {
  gw: GatewayClient
  onClose: () => void
  sessionId: string | null
  t: Theme
}
