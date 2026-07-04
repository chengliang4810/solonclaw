import type { Msg, Role } from '../types.js'

import { appendToolShelfMessage } from './liveProgress.js'

export const appendTranscriptMessage = (prev: Msg[], msg: Msg): Msg[] => {
  if (msg.role === 'system' && !msg.kind && prev.slice(-16).some(item => item.role === 'system' && !item.kind && item.text === msg.text)) {
    return prev
  }

  return appendToolShelfMessage(prev, msg)
}

export const upsert = (prev: Msg[], role: Role, text: string): Msg[] =>
  prev.at(-1)?.role === role ? [...prev.slice(0, -1), { role, text }] : [...prev, { role, text }]
