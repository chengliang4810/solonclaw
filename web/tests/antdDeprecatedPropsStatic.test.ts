import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { extname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const roots = [
  new URL('../src/views', import.meta.url),
  new URL('../src/components', import.meta.url),
]

const deprecatedProps = [
  { component: 'Spin', prop: 'tip', replacement: 'description' },
  { component: 'Drawer', prop: 'width', replacement: 'style.width' },
  { component: 'Input', prop: 'clearable', replacement: 'allow-clear' },
  { component: 'InputNumber', prop: 'clearable', replacement: 'remove unsupported prop' },
]

function vueFiles(dir: URL | string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(typeof dir === 'string' ? dir : fileURLToPath(dir), entry.name)
    if (entry.isDirectory()) return vueFiles(path)
    return extname(path) === '.vue' ? [path] : []
  })
}

const sourceRoot = fileURLToPath(new URL('../src', import.meta.url))
const violations = roots.flatMap((root) =>
  vueFiles(root).flatMap((file) => {
    const source = readFileSync(file, 'utf8')
    return deprecatedProps
      .filter(({ component, prop }) =>
        [...source.matchAll(new RegExp(`<${component}\\b[^>]*>`, 'g'))]
          .some(([tag]) => new RegExp(`(?:\\s|:)${prop}(?:\\s|=|>)`).test(tag)),
      )
      .map(({ component, prop, replacement }) => `${relative(sourceRoot, file)} uses ${component}.${prop}; use ${replacement}`)
  }),
)

assert.deepEqual(violations, [], 'antd-next deprecated props should not be used')
