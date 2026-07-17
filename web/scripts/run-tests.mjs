import { readFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'

// 自动执行全部命名为 test:* 的独立测试脚本，避免新增脚本后漏出 CI 门禁。
const packageJson = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'))
const testScripts = Object.keys(packageJson.scripts || {})
  .filter(name => name.startsWith('test:'))
  .sort()
const npmCommand = process.platform === 'win32' ? 'npm.cmd' : 'npm'

for (const testScript of testScripts) {
  const result = spawnSync(npmCommand, ['run', testScript], {
    env: process.env,
    stdio: 'inherit',
  })
  if (result.status !== 0) {
    process.exit(result.status || 1)
  }
}
