import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

describe('package scripts', () => {
  it('builds the local Ink package before npm start imports it', () => {
    const pkg = JSON.parse(readFileSync(new URL('../../package.json', import.meta.url), 'utf8')) as {
      scripts?: Record<string, string>
    }

    expect(pkg.scripts?.prestart).toBe('npm run build --prefix packages/solonclaw-ink')
  })
})
