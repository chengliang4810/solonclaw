import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../src/composables/useTheme.ts', import.meta.url), 'utf8')

assert.ok(!/^const mediaQuery = window\.matchMedia/m.test(source), 'useTheme should not create matchMedia at module load')
assert.ok(!/^mediaQuery\.addEventListener/m.test(source), 'useTheme should not register media listeners at module load')
assert.ok(source.includes('ensureThemeInitialized'), 'useTheme should lazily initialize browser theme state')
assert.ok(source.includes('onScopeDispose'), 'useTheme should clean up browser listeners when component scopes dispose')
assert.ok(source.includes('removeEventListener'), 'useTheme should remove matchMedia listener during cleanup')
