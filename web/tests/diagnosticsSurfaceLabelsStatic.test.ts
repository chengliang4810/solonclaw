import { readFileSync } from 'node:fs'
import assert from 'node:assert/strict'

const runner = readFileSync(
  new URL('../../src/main/java/com/jimuqu/solon/claw/web/DashboardSecurityProbeRunner.java', import.meta.url),
  'utf8',
)
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

function probeSurfaces() {
  const surfaces = new Set<string>()
  for (const match of runner.matchAll(/return\s+(?:skippedPolicyProbeItem|policyProbeItem)\s*\(([\s\S]*?)\);/g)) {
    const surface = match[1].match(/"([a-z0-9_]+)"/)?.[1]
    if (surface) {
      surfaces.add(surface)
    }
  }
  return [...surfaces].sort()
}

function surfaceLabelKeys(source: string) {
  const marker = 'surfaceLabels: {'
  const start = source.indexOf(marker)
  assert.notEqual(start, -1, 'locale should define diagnostics.surfaceLabels')

  const open = source.indexOf('{', start)
  let depth = 0
  for (let index = open; index < source.length; index += 1) {
    if (source[index] === '{') {
      depth += 1
    } else if (source[index] === '}') {
      depth -= 1
      if (depth === 0) {
        return new Set([...source.slice(open + 1, index).matchAll(/^\s*([A-Za-z0-9_]+):\s*['"`]/gm)].map((match) => match[1]))
      }
    }
  }
  throw new Error('locale diagnostics.surfaceLabels block should close')
}

for (const [name, source] of [
  ['zh', zh],
  ['en', en],
] as const) {
  const labels = surfaceLabelKeys(source)
  const missing = probeSurfaces().filter((surface) => !labels.has(surface))
  assert.deepEqual(missing, [], `${name} diagnostics.surfaceLabels missing backend probe surfaces`)
}
