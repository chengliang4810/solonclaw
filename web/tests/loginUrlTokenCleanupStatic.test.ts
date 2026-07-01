import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const main = readFileSync(new URL('../src/main.ts', import.meta.url), 'utf8')

assert.ok(
  main.includes('urlParams.delete(\'token\')'),
  'main.ts should remove login token from the normal URL query',
)
assert.ok(
  main.includes('hashParams.delete(\'token\')'),
  'main.ts should remove login token from the hash route query',
)
assert.ok(
  !main.includes('urlParams.set(\'token\', hashToken)'),
  'hash-route login token must not be moved back into the normal URL query',
)
assert.ok(
  main.includes('if (urlToken)'),
  'main.ts should still preserve the login token in memory before cleaning the URL',
)
assert.ok(
  main.includes('dashboardHashRouteForPath(window.location.pathname)'),
  'main.ts should map direct dashboard paths to hash routes before the router starts',
)
assert.ok(
  main.includes('searchToken || hashToken || directRouteHash'),
  'main.ts should normalize direct dashboard paths even when no login token is present',
)
