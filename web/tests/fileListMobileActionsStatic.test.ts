import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(
  new URL('../src/components/solonclaw/files/FileList.vue', import.meta.url),
  'utf8',
)

assert.match(source, /\.file-actions\s*\{[\s\S]*?opacity:\s*0/)

const mobileBlock = source.slice(source.indexOf('@media (max-width: $breakpoint-mobile)'))

assert.match(
  mobileBlock,
  /\.file-actions\s*\{[\s\S]*?opacity:\s*1/,
  'mobile file rows should expose actions because touch devices do not have reliable hover',
)
