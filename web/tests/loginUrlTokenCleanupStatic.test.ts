import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const main = readFileSync(new URL('../src/main.ts', import.meta.url), 'utf8')
const helper = readFileSync(new URL('../src/shared/loginUrlToken.ts', import.meta.url), 'utf8')

assert.ok(
  helper.includes('urlParams.delete(\'token\')'),
  'login URL helper should remove login token from the normal URL query',
)
assert.ok(
  helper.includes('hashParams.delete(\'token\')'),
  'login URL helper should remove login token from the hash route query',
)
assert.ok(
  !helper.includes('urlParams.set(\'token\', hashToken)'),
  'hash-route login token must not be moved back into the normal URL query',
)
assert.ok(
  main.includes('if (loginUrl.token)'),
  'main.ts should still preserve the login token in memory before cleaning the URL',
)
assert.ok(
  main.includes('normalizeLoginTokenUrl(window.location, dashboardHashRouteForPath)'),
  'main.ts should map direct dashboard paths to hash routes before the router starts',
)
assert.ok(
  helper.includes('!searchToken && !hashToken && !directRouteHash'),
  'main.ts should normalize direct dashboard paths even when no login token is present',
)
assert.ok(
  !main.includes("import router from './router'"),
  'main.ts must not create hash router before direct-route URL normalization runs',
)
assert.ok(
  main.includes("await import('./router')"),
  'main.ts should import the router after URL normalization',
)
