export function isPositiveRpcAck(value: unknown): boolean {
  if (!value) {
    return false
  }

  if (typeof value === 'object' && 'ok' in value) {
    return (value as { ok?: unknown }).ok !== false
  }

  return true
}

export function shouldDismissOverlayAfterRpcAck(value: unknown): boolean {
  return isPositiveRpcAck(value)
}
