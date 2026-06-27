import { describe, expect, it } from 'vitest'

import { caduceus, logo } from '../banner.js'
import { DEFAULT_THEME } from '../theme.js'

const text = (lines: [string, string][]) => lines.map(([, line]) => line)

describe('banner art', () => {
  it('renders Solon Claw with a word gap and no title hyphen', () => {
    const lines = text(logo(DEFAULT_THEME.color))

    expect(lines[2]).not.toContain('██║█████╗██║')
    expect(lines[3]).not.toContain('██║╚════╝██║')
    expect(lines[0]).toContain('██╗       ██████╗')
    expect(lines[2]).toContain('██║      ██║')
  })

  it('uses Solon favicon art for the session hero', () => {
    const lines = text(caduceus(DEFAULT_THEME.color))

    expect(lines).toContain('      ███████████████████████      ')
    expect(lines).toContain('    ████████           ████████    ')
    expect(lines.join('\n')).not.toContain('██████████      ██████████████')
  })
})
