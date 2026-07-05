import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

const source = readFileSync(new URL('../components/appOverlays.tsx', import.meta.url), 'utf8')

describe('FloatingOverlays layout', () => {
  it('keeps command overlays in normal flow so setup-required screens can see them', () => {
    expect(source).not.toContain('bottom="100%"')
    expect(source).not.toContain('position="absolute"')
  })
})
