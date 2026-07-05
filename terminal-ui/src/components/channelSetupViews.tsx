import { Box, Link, Text } from '@solonclaw/ink'

import type { ChannelOption, ChannelQrResponse, ChannelSetupField } from '../gatewayTypes.js'
import type { Theme } from '../theme.js'

import { channelQrMessage, channelQrResponseStatusActive, channelQrStatus, channelQrUrl, channelSupportsQr } from './channelQr.js'
import { OverlayHint, windowItems } from './overlayControls.js'

/** 通道设置视图只展示后端返回的通道状态，协议字段解释保持在 channelQr/RPC 层。 */
export const channelSetupListRowLabel = (channel: ChannelOption): string => {
  const status = channel.configured ? 'configured' : 'not configured'
  const qrLabel = channelSupportsQr(channel) ? ' · QR' : ''

  return `${channel.label} · ${status}${qrLabel}`
}

export const channelSetupFieldValueLabel = (field: ChannelSetupField, value: string): string => {
  if (field.secret && value) {
    return '•'.repeat(Math.min(value.length, 40))
  }

  return value
}

export function ChannelSetupLoading({ t }: ChannelViewThemeProps) {
  return <Text color={t.color.muted}>loading channels…</Text>
}

export function ChannelSetupError({ err, t, width }: ChannelSetupErrorProps) {
  return (
    <Box flexDirection="column" width={width}>
      <Text color={t.color.label}>error: {err}</Text>
      <OverlayHint t={t}>Esc/q cancel</OverlayHint>
    </Box>
  )
}

export function ChannelSetupEmpty({ t, width }: ChannelViewBoxProps) {
  return (
    <Box flexDirection="column" width={width}>
      <Text color={t.color.muted}>no channels available</Text>
      <OverlayHint t={t}>Esc/q cancel</OverlayHint>
    </Box>
  )
}

export function ChannelQrSetupView({ channel, err, qr, qrLoading, t, width }: ChannelQrSetupViewProps) {
  const qrUrl = channelQrUrl(qr)
  const status = qr ? channelQrStatus(qr) : (qrLoading ? 'starting' : 'wait')
  const message = err || channelQrMessage(qr) || '等待扫码绑定状态。'

  return (
    <Box flexDirection="column" width={width}>
      <Text bold color={t.color.accent} wrap="truncate-end">
        Scan to bind {channel.label}
      </Text>
      <Text color={t.color.muted} wrap="truncate-end">
        status: {status}
      </Text>
      <Text color={err ? t.color.label : t.color.muted} wrap="truncate-end">
        {message}
      </Text>
      {qrUrl ? (
        <Link url={qrUrl}>
          <Text color={t.color.accent} wrap="truncate-end">
            {qrUrl}
          </Text>
        </Link>
      ) : (
        <Text color={t.color.muted} wrap="truncate-end">
          {qrLoading ? 'starting QR setup…' : 'waiting for QR URL…'}
        </Text>
      )}
      <Text color={t.color.muted} wrap="truncate-end">
        {channelQrResponseStatusActive(qr) ? 'polling every 1.5s' : ' '}
      </Text>
      <OverlayHint t={t}>Esc back · q close</OverlayHint>
    </Box>
  )
}

export function ChannelFieldsView({ channel, err, field, fieldIdx, fieldCount, saving, t, value, width }: ChannelFieldsViewProps) {
  const shown = channelSetupFieldValueLabel(field, value)

  return (
    <Box flexDirection="column" width={width}>
      <Text bold color={t.color.accent} wrap="truncate-end">
        Configure {channel.label}
      </Text>
      <Text color={t.color.muted} wrap="truncate-end">
        {fieldIdx + 1}/{fieldCount} · {field.label || field.key}
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

export function ChannelSavedView({ channel, t, width }: ChannelSavedViewProps) {
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

export function ChannelListView({ channelIdx, channels, t, visible, width }: ChannelListViewProps) {
  const rows = channels.map(channelSetupListRowLabel)
  const { items, offset } = windowItems(rows, channelIdx, visible)

  return (
    <Box flexDirection="column" width={width}>
      <Text bold color={t.color.accent} wrap="truncate-end">
        Channel setup
      </Text>
      <Text color={t.color.muted} wrap="truncate-end">
        Domestic channels only · Enter configure · r scan
      </Text>
      <Text color={t.color.muted} wrap="truncate-end">
        {offset > 0 ? ` ↑ ${offset} more` : ' '}
      </Text>
      {Array.from({ length: visible }, (_, i) => {
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
        {offset + visible < rows.length ? ` ↓ ${rows.length - offset - visible} more` : ' '}
      </Text>
      <OverlayHint t={t}>↑/↓ select · Enter configure · r scan · Esc/q close</OverlayHint>
    </Box>
  )
}

type ChannelViewThemeProps = {
  readonly t: Theme
}

type ChannelViewBoxProps = ChannelViewThemeProps & {
  readonly width: number
}

type ChannelSetupErrorProps = ChannelViewBoxProps & {
  readonly err: string
}

type ChannelQrSetupViewProps = ChannelViewBoxProps & {
  readonly channel: ChannelOption
  readonly err: string
  readonly qr: ChannelQrResponse | null
  readonly qrLoading: boolean
}

type ChannelFieldsViewProps = ChannelViewBoxProps & {
  readonly channel: ChannelOption
  readonly err: string
  readonly field: ChannelSetupField
  readonly fieldCount: number
  readonly fieldIdx: number
  readonly saving: boolean
  readonly value: string
}

type ChannelSavedViewProps = ChannelViewBoxProps & {
  readonly channel: ChannelOption
}

type ChannelListViewProps = ChannelViewBoxProps & {
  readonly channelIdx: number
  readonly channels: readonly ChannelOption[]
  readonly visible: number
}
