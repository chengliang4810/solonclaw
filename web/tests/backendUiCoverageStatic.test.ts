import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'

const diagnosticsApiFile = new URL('../src/api/solonclaw/diagnostics.ts', import.meta.url)
const diagnosticsViewFile = new URL('../src/views/solonclaw/DiagnosticsView.vue', import.meta.url)
const approvalEventsPanelFile = new URL('../src/components/solonclaw/diagnostics/ApprovalEventsPanel.vue', import.meta.url)
const configApiFile = new URL('../src/api/solonclaw/config.ts', import.meta.url)
const settingsViewFile = new URL('../src/views/solonclaw/SettingsView.vue', import.meta.url)
const zhFile = new URL('../src/i18n/locales/zh.ts', import.meta.url)
const enFile = new URL('../src/i18n/locales/en.ts', import.meta.url)

const diagnosticsApi = readFileSync(diagnosticsApiFile, 'utf8')
const diagnosticsView = readFileSync(diagnosticsViewFile, 'utf8')
const configApi = readFileSync(configApiFile, 'utf8')
const settingsView = readFileSync(settingsViewFile, 'utf8')
const zh = readFileSync(zhFile, 'utf8')
const en = readFileSync(enFile, 'utf8')

assert.ok(diagnosticsApi.includes('fetchApprovalEvents'), 'diagnostics API should expose runtime approval events')
assert.ok(diagnosticsApi.includes('/api/approval/events?limit='), 'runtime approval events should call backend events endpoint')
assert.ok(existsSync(approvalEventsPanelFile), 'diagnostics page should render approval events with a focused component')
assert.ok(diagnosticsView.includes('fetchApprovalEvents'), 'diagnostics view should load runtime approval events')
assert.ok(diagnosticsView.includes('ApprovalEventsPanel'), 'diagnostics view should mount runtime approval events panel')

assert.ok(configApi.includes('fetchConfigDefaults'), 'config API should expose backend config defaults')
assert.ok(configApi.includes("'/api/config/defaults'"), 'config defaults should call backend defaults endpoint')
assert.ok(settingsView.includes('fetchConfigDefaults'), 'settings view should load config defaults')
assert.ok(settingsView.includes('settings.configDiagnostics.defaults'), 'settings view should render config defaults')

assert.ok(zh.includes("approvalEvents: '运行时审批事件'"), 'Chinese locale should include approval events label')
assert.ok(en.includes("approvalEvents: 'Runtime approval events'"), 'English locale should include approval events label')
assert.ok(zh.includes("defaults: '默认配置'"), 'Chinese locale should include config defaults label')
assert.ok(en.includes("defaults: 'Config defaults'"), 'English locale should include config defaults label')
