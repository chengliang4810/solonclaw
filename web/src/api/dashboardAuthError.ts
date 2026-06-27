const DASHBOARD_ORIGIN_REJECTION = 'Forbidden dashboard request origin'

export function isDashboardOriginRejected(status: number, body: string): boolean {
  return status === 403 && body.includes(DASHBOARD_ORIGIN_REJECTION)
}
