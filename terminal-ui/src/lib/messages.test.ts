import { describe, expect, it } from 'vitest'

import { appendTranscriptMessage } from './messages.js'

describe('appendTranscriptMessage', () => {
  it('merges adjacent tool-only shelves into one transcript row', () => {
    const out = appendTranscriptMessage([{ kind: 'trail', role: 'system', text: '', tools: ['Terminal("one") ✓'] }], {
      kind: 'trail',
      role: 'system',
      text: '',
      tools: ['Terminal("two") ✓']
    })

    expect(out).toEqual([
      { kind: 'trail', role: 'system', text: '', tools: ['Terminal("one") ✓', 'Terminal("two") ✓'] }
    ])
  })

  it('merges tool shelves into the nearest thinking shelf', () => {
    const out = appendTranscriptMessage(
      [{ kind: 'trail', role: 'system', text: '', thinking: 'plan', tools: ['Terminal("one") ✓'] }],
      { kind: 'trail', role: 'system', text: '', tools: ['Terminal("two") ✓'] }
    )

    expect(out).toEqual([
      { kind: 'trail', role: 'system', text: '', thinking: 'plan', tools: ['Terminal("one") ✓', 'Terminal("two") ✓'] }
    ])
  })

  it('deduplicates adjacent plain system messages', () => {
    const first = appendTranscriptMessage([], { role: 'system', text: 'error: gateway not connected: config.get' })
    const second = appendTranscriptMessage(first, { role: 'system', text: 'error: gateway not connected: config.get' })

    expect(second).toEqual([{ role: 'system', text: 'error: gateway not connected: config.get' }])
  })

  it('deduplicates repeated plain system messages even when startup errors interleave', () => {
    const first = appendTranscriptMessage([], { role: 'system', text: 'error: gateway not connected: config.get' })
    const second = appendTranscriptMessage(first, { role: 'system', text: '错误：后端已断开' })
    const third = appendTranscriptMessage(second, { role: 'system', text: 'error: gateway not connected: config.get' })

    expect(third).toEqual([
      { role: 'system', text: 'error: gateway not connected: config.get' },
      { role: 'system', text: '错误：后端已断开' }
    ])
  })
})
