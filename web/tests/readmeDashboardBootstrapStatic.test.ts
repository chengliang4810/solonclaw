import { readFileSync } from 'node:fs'
import { strict as assert } from 'node:assert'

const zh = readFileSync(new URL('../../README.md', import.meta.url), 'utf8')
const en = readFileSync(new URL('../../README_EN.md', import.meta.url), 'utf8')

for (const [name, text] of Object.entries({ README: zh, README_EN: en })) {
  assert.ok(
    text.includes('solonclaw.dashboard.accessToken'),
    `${name} should document the dashboard access token startup property`,
  )
  assert.ok(
    text.includes('workspace/config.yml'),
    `${name} should explain where the first dashboard token is persisted`,
  )
}
