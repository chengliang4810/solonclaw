import { useStdout } from '@solonclaw/ink'
import { useEffect, useMemo, useState } from 'react'

import type { GatewayClient } from '../gatewayClient.js'
import type { ChannelOption, ChannelQrResponse } from '../gatewayTypes.js'
import { rpcErrorMessage } from '../lib/rpc.js'
import type { Theme } from '../theme.js'

import { channelQrMessage, channelQrStatusActive, channelSupportsQr } from './channelQr.js'
import { useChannelSetupInput } from './channelSetupInput.js'
import { type ChannelSetupStage } from './channelSetupKeys.js'
import { loadChannelOptions, refreshChannelQr, saveChannelConfig, startChannelQr } from './channelSetupRpc.js'
import {
  ChannelFieldsView,
  ChannelListView,
  ChannelQrSetupView,
  ChannelSavedView,
  ChannelSetupEmpty,
  ChannelSetupError,
  ChannelSetupLoading
} from './channelSetupViews.js'
import { useOverlayKeys } from './overlayControls.js'

const VISIBLE = 10
const MIN_WIDTH = 44
const MAX_WIDTH = 90

export function ChannelSetup({ gw, onClose, sessionId, t }: ChannelSetupProps) {
  const [channels, setChannels] = useState<ChannelOption[]>([])
  const [channelIdx, setChannelIdx] = useState(0)
  const [err, setErr] = useState('')
  const [fieldIdx, setFieldIdx] = useState(0)
  const [loading, setLoading] = useState(true)
  const [qr, setQr] = useState<ChannelQrResponse | null>(null)
  const [qrLoading, setQrLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [stage, setStage] = useState<ChannelSetupStage>('channel')
  const [values, setValues] = useState<Record<string, string>>({})

  const { stdout } = useStdout()
  const width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, (stdout?.columns ?? 80) - 6))

  useEffect(() => {
    loadChannelOptions(gw, sessionId)
      .then(r => {
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

  const resetToChannelList = () => {
    setStage('channel')
    setValues({})
    setFieldIdx(0)
  }

  const back = () => {
    if (stage === 'saved') {
      resetToChannelList()

      return
    }

    if (stage === 'fields') {
      if (fieldIdx > 0) {
        setFieldIdx(v => v - 1)

        return
      }

      resetToChannelList()

      return
    }

    if (stage === 'qr') {
      setStage('channel')
      setQr(null)

      return
    }

    onClose()
  }

  useOverlayKeys({ disabled: stage === 'channel' || stage === 'fields' || stage === 'qr', onBack: back, onClose })

  const save = (overrideValues?: Record<string, string>) => {
    if (!channel || saving) {
      return
    }

    const nextValues = overrideValues ?? values
    setSaving(true)
    setErr('')
    saveChannelConfig(gw, channel.key, nextValues, sessionId)
      .then(r => {
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

  const startQr = () => {
    if (!channel || qrLoading || !channelSupportsQr(channel)) {
      return
    }

    setQrLoading(true)
    setErr('')
    setQr(null)
    setStage('qr')
    startChannelQr(gw, channel.key, sessionId)
      .then(r => {
        setQr(r)
        setErr(r.ok === false ? channelQrMessage(r) || 'failed to start QR setup' : '')
        setQrLoading(false)
      })
      .catch((e: unknown) => {
        setErr(rpcErrorMessage(e))
        setQrLoading(false)
      })
  }

  useEffect(() => {
    if (stage !== 'qr' || !channel || !qr?.ticket || !channelQrStatusActive(qr.status)) {
      return
    }

    const ticket = qr.ticket
    const timer = setTimeout(() => {
      refreshChannelQr(gw, channel.key, ticket, sessionId)
        .then(r => {
          setQr(r)
          setErr(r.ok === false ? channelQrMessage(r) || 'failed to refresh QR setup' : '')
        })
        .catch((e: unknown) => {
          setErr(rpcErrorMessage(e))
        })
    }, 1500)

    return () => clearTimeout(timer)
  }, [channel, gw, qr, sessionId, stage])

  useChannelSetupInput({
    back,
    channel,
    channelIdx,
    channels,
    field,
    fieldIdx,
    fields,
    loading,
    onClose,
    resetToChannelList,
    save,
    saving,
    setChannelIdx,
    setFieldIdx,
    setStage,
    setValues,
    stage,
    startQr
  })

  if (loading) {
    return <ChannelSetupLoading t={t} />
  }

  if (err && stage === 'channel') {
    return <ChannelSetupError err={err} t={t} width={width} />
  }

  if (!channels.length) {
    return <ChannelSetupEmpty t={t} width={width} />
  }

  if (stage === 'qr' && channel) {
    return <ChannelQrSetupView channel={channel} err={err} qr={qr} qrLoading={qrLoading} t={t} width={width} />
  }

  if (stage === 'fields' && channel && field) {
    const value = values[field.key] ?? ''

    return (
      <ChannelFieldsView
        channel={channel}
        err={err}
        field={field}
        fieldCount={fields.length}
        fieldIdx={fieldIdx}
        saving={saving}
        t={t}
        value={value}
        width={width}
      />
    )
  }

  if (stage === 'saved' && channel) {
    return <ChannelSavedView channel={channel} t={t} width={width} />
  }

  return <ChannelListView channelIdx={channelIdx} channels={channels} t={t} visible={VISIBLE} width={width} />
}

interface ChannelSetupProps {
  gw: GatewayClient
  onClose: () => void
  sessionId: string | null
  t: Theme
}
