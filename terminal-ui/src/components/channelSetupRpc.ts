import type { GatewayClient } from '../gatewayClient.js'
import type { ChannelOptionsResponse, ChannelQrResponse, ChannelSaveResponse } from '../gatewayTypes.js'
import { asRpcResult, type RpcResult } from '../lib/rpc.js'

export const loadChannelOptions = (gw: GatewayClient, sessionId: string | null): Promise<ChannelOptionsResponse> =>
  requestResult(gw, 'channel.options', sessionParams(sessionId))

export const saveChannelConfig = (
  gw: GatewayClient,
  channel: string,
  values: Record<string, string>,
  sessionId: string | null
): Promise<ChannelSaveResponse> =>
  requestResult(gw, 'channel.save', {
    channel,
    values,
    ...sessionParams(sessionId)
  })

export const startChannelQr = (
  gw: GatewayClient,
  channel: string,
  sessionId: string | null
): Promise<ChannelQrResponse> =>
  requestResult(gw, 'channel.qr.start', {
    channel,
    ...sessionParams(sessionId)
  })

export const refreshChannelQr = (
  gw: GatewayClient,
  channel: string,
  ticket: string,
  sessionId: string | null
): Promise<ChannelQrResponse> =>
  requestResult(gw, 'channel.qr.get', {
    channel,
    ticket,
    ...sessionParams(sessionId)
  })

const sessionParams = (sessionId: string | null): Record<string, string> => (sessionId ? { session_id: sessionId } : {})

const requestResult = async <T extends RpcResult>(gw: GatewayClient, method: string, params: Record<string, unknown>): Promise<T> => {
  const raw = await gw.request<T>(method, params)
  const result = asRpcResult<T>(raw)

  if (!result) {
    throw new Error(`invalid response: ${method}`)
  }

  return result
}
