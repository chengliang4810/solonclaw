import { type Key, useInput } from '@solonclaw/ink'
import type { Dispatch, SetStateAction } from 'react'

import type { ChannelOption, ChannelSetupField } from '../gatewayTypes.js'

import { channelSupportsQr } from './channelQr.js'
import {
  canMoveChannelDown,
  canMoveChannelUp,
  type ChannelSetupStage,
  INITIAL_CHANNEL_VALUES,
  nextFieldIndex
} from './channelSetupKeys.js'

export function useChannelSetupInput(controller: ChannelSetupInputController): void {
  useInput((ch, key) => handleChannelSetupInput(ch, key, controller))
}

function handleChannelSetupInput(ch: string, key: Key, c: ChannelSetupInputController): void {
  if (c.loading || c.saving) {
    return
  }

  if (c.stage === 'channel') {
    handleChannelListInput(ch, key, c)

    return
  }

  if (c.stage === 'saved') {
    if (key.escape || key.return || ch === 'q') {
      c.resetToChannelList()
    }

    return
  }

  if (c.stage === 'qr') {
    if (key.escape) {
      c.back()

      return
    }

    if (ch === 'q') {
      c.onClose()
    }

    return
  }

  handleFieldInput(ch, key, c)
}

function handleChannelListInput(ch: string, key: Key, c: ChannelSetupInputController): void {
  if (key.escape || ch === 'q') {
    c.onClose()

    return
  }

  if (key.upArrow && canMoveChannelUp(c.channelIdx)) {
    c.setChannelIdx(v => v - 1)

    return
  }

  if (key.downArrow && canMoveChannelDown(c.channelIdx, c.channels)) {
    c.setChannelIdx(v => v + 1)

    return
  }

  if (key.return && c.channel) {
    c.setValues(INITIAL_CHANNEL_VALUES)
    c.setFieldIdx(0)
    c.fields.length ? c.setStage('fields') : c.save(INITIAL_CHANNEL_VALUES)

    return
  }

  if (ch === 'r' && c.channel && channelSupportsQr(c.channel)) {
    c.startQr()
  }
}

function handleFieldInput(ch: string, key: Key, c: ChannelSetupInputController): void {
  const field = c.field

  if (!field) {
    return
  }

  if (key.escape) {
    c.back()

    return
  }

  if (key.return) {
    const nextIndex = nextFieldIndex(c.fieldIdx, c.fields)

    if (nextIndex !== null) {
      c.setFieldIdx(nextIndex)

      return
    }

    c.save()

    return
  }

  if (key.backspace || key.delete) {
    c.setValues(prev => ({ ...prev, [field.key]: (prev[field.key] ?? '').slice(0, -1) }))

    return
  }

  if (key.ctrl && ch === 'u') {
    c.setValues(prev => ({ ...prev, [field.key]: '' }))

    return
  }

  if (ch && !key.ctrl && !key.meta && ch.length === 1 && ch >= ' ') {
    c.setValues(prev => ({ ...prev, [field.key]: (prev[field.key] ?? '') + ch }))
  }
}

type ChannelSetupInputController = {
  readonly back: () => void
  readonly channel: ChannelOption | undefined
  readonly channelIdx: number
  readonly channels: readonly ChannelOption[]
  readonly field: ChannelSetupField | undefined
  readonly fieldIdx: number
  readonly fields: readonly ChannelSetupField[]
  readonly loading: boolean
  readonly onClose: () => void
  readonly resetToChannelList: () => void
  readonly save: (overrideValues?: Record<string, string>) => void
  readonly saving: boolean
  readonly setChannelIdx: Dispatch<SetStateAction<number>>
  readonly setFieldIdx: Dispatch<SetStateAction<number>>
  readonly setStage: Dispatch<SetStateAction<ChannelSetupStage>>
  readonly setValues: Dispatch<SetStateAction<Record<string, string>>>
  readonly stage: ChannelSetupStage
  readonly startQr: () => void
}
