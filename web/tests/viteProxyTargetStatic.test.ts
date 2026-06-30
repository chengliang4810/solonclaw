import { readFileSync } from 'node:fs'
import assert from 'node:assert/strict'

const viteConfig = readFileSync(new URL('../vite.config.ts', import.meta.url), 'utf8')

assert.ok(
  viteConfig.includes('SOLONCLAW_SERVER_URL'),
  'vite dev proxy should allow overriding the backend target with SOLONCLAW_SERVER_URL',
)
assert.ok(
  viteConfig.includes('http://127.0.0.1:8080'),
  'vite dev proxy should keep the local backend as its default target',
)
assert.ok(
  /['"]\/api['"]:\s*backendTarget/.test(viteConfig)
    && /['"]\/health['"]:\s*backendTarget/.test(viteConfig)
    && /['"]\/upload['"]:\s*backendTarget/.test(viteConfig),
  'vite dev proxy should route every backend path through the shared target',
)
