import type { ChannelOption, ChannelSetupField } from '../gatewayTypes.js'

export type ChannelSetupStage = 'channel' | 'fields' | 'qr' | 'saved'

export const INITIAL_CHANNEL_VALUES = { enabled: 'true' } as const

export const canMoveChannelUp = (index: number): boolean => index > 0

export const canMoveChannelDown = (index: number, channels: readonly ChannelOption[]): boolean => index < channels.length - 1

export const nextFieldIndex = (index: number, fields: readonly ChannelSetupField[]): number | null =>
  index < fields.length - 1 ? index + 1 : null
