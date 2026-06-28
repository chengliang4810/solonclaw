import { describe, expect, it } from 'vitest'

import { asRpcResult, rpcErrorMessage } from '../lib/rpc.js'

describe('asRpcResult', () => {
  it('keeps plain object payloads', () => {
    expect(asRpcResult({ ok: true, value: 'x' })).toEqual({ ok: true, value: 'x' })
  })

  it('rejects missing or non-object payloads', () => {
    expect(asRpcResult(undefined)).toBeNull()
    expect(asRpcResult(null)).toBeNull()
    expect(asRpcResult('oops')).toBeNull()
    expect(asRpcResult(['bad'])).toBeNull()
  })
})

describe('rpcErrorMessage', () => {
  it('prefers Error messages', () => {
    expect(rpcErrorMessage(new Error('boom'))).toBe('boom')
  })

  it('removes backend exception class prefixes from user-facing errors', () => {
    expect(rpcErrorMessage(new Error('IllegalStateException: LLM apiUrl 被安全策略阻断'))).toBe('LLM apiUrl 被安全策略阻断')
    expect(rpcErrorMessage('java.lang.IllegalArgumentException: 参数错误')).toBe('参数错误')
  })

  it('falls back for unknown errors', () => {
    expect(rpcErrorMessage('broken')).toBe('broken')
    expect(rpcErrorMessage({ code: 500 })).toBe('request failed')
  })
})
