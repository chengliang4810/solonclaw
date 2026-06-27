import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/diagnostics.ts', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/i18n/locales/zh.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/i18n/locales/en.ts', import.meta.url), 'utf8')

assert.ok(api.includes('probeSubprocessEnvironment'), 'diagnostics API should expose subprocess environment probe')
assert.ok(api.includes("'/api/diagnostics/subprocess-environment/probe'"), 'probe should call backend endpoint')
assert.ok(view.includes('handleSubprocessEnvProbe'), 'diagnostics view should run subprocess env probe')
assert.ok(view.includes("t('diagnostics.subprocessEnvProbe')"), 'diagnostics view should render probe section')
assert.ok(zh.includes("subprocessEnvProbe: '子进程环境探针'"), 'Chinese locale should include subprocess env probe label')
assert.ok(en.includes("subprocessEnvProbe: 'Subprocess environment probe'"), 'English locale should include subprocess env probe label')
