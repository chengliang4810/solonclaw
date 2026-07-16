import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

/** 读取终端 UI 源文件，验证轮询入口都经过合并请求。 */
const source = (relativePath: string) => readFileSync(new URL(relativePath, import.meta.url), 'utf8')

describe('polling RPC wiring', () => {
  it('routes every recurring gateway poll through GatewayClient.poll', () => {
    const mainApp = source('../app/useMainApp.ts')
    const configSync = source('../app/useConfigSync.ts')
    const sessionSwitcher = source('../components/activeSessionSwitcher.tsx')

    expect(mainApp).toContain('gw.poll<SessionActiveListResponse>')
    expect(configSync).toContain('await gw.poll<T>(pollingKey, method, params)')
    expect(sessionSwitcher).toContain('gw.poll<SessionActiveListResponse>')
  })

  it('keeps local single-flight and stale-result guards around recurring polls', () => {
    const mainApp = source('../app/useMainApp.ts')
    const configSync = source('../app/useConfigSync.ts')
    const sessionSwitcher = source('../components/activeSessionSwitcher.tsx')

    expect(mainApp).toContain('if (stopped || inFlight)')
    expect(configSync).toContain('if (stopped || inFlight)')
    expect(sessionSwitcher).toContain('if (polling && activeLoadsRef.current > 0)')
    expect(sessionSwitcher).toContain('contextId !== loadContextRef.current')
  })
})
