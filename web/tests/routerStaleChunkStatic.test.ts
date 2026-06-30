import { readFileSync } from 'node:fs'
import assert from 'node:assert/strict'

const router = readFileSync(new URL('../src/router/index.ts', import.meta.url), 'utf8')

assert.ok(router.includes('router.onError'), 'router should handle async route load failures')
assert.ok(
  router.includes('Failed to fetch dynamically imported module')
    && router.includes('Loading chunk \\d+ failed'),
  'router should recognize stale dynamic chunk errors',
)
assert.ok(router.includes('sessionStorage'), 'router should guard reload loops across page reloads')
assert.ok(router.includes('window.location.reload()'), 'router should reload once after stale chunk errors')
