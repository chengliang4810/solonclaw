import { describe, expect, it } from 'vitest'

import { isPositiveRpcAck, shouldDismissOverlayAfterRpcAck } from '../app/rpcAck.js'

describe('isPositiveRpcAck', () => {
  it('does not treat explicit ok=false responses as successful prompt acknowledgements', () => {
    expect(isPositiveRpcAck({ ok: false, warning: 'missing_session_id' })).toBe(false)
  })

  it('keeps approval overlays open when approval.respond explicitly fails', () => {
    expect(shouldDismissOverlayAfterRpcAck({ ok: false, warning: 'missing_session_id' })).toBe(false)
  })

  it('keeps successful and legacy truthy responses accepted', () => {
    expect(isPositiveRpcAck({ ok: true })).toBe(true)
    expect(isPositiveRpcAck({ output: 'done' })).toBe(true)
  })

  it('rejects nullish responses', () => {
    expect(isPositiveRpcAck(null)).toBe(false)
    expect(isPositiveRpcAck(undefined)).toBe(false)
  })
})
