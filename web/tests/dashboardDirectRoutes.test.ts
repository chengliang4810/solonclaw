import assert from 'node:assert/strict'
import { dashboardHashRouteForPath } from '../src/shared/dashboardDirectRoutes.ts'

assert.equal(dashboardHashRouteForPath('/logs'), '#/solonclaw/logs')
assert.equal(dashboardHashRouteForPath('/channels'), '#/solonclaw/channels')
assert.equal(dashboardHashRouteForPath('/cron'), '#/solonclaw/jobs')
assert.equal(dashboardHashRouteForPath('/config'), '#/solonclaw/settings')
assert.equal(dashboardHashRouteForPath('/login'), '#/')
assert.equal(dashboardHashRouteForPath('/status'), '#/solonclaw/diagnostics')
assert.equal(dashboardHashRouteForPath('/sessions'), '#/solonclaw/runs')
assert.equal(dashboardHashRouteForPath('/analytics'), '#/solonclaw/usage')
assert.equal(dashboardHashRouteForPath('/memory'), '#/solonclaw/persona/journal')
assert.equal(dashboardHashRouteForPath('/workspace'), '#/solonclaw/files')
assert.equal(dashboardHashRouteForPath('/env'), '#/solonclaw/settings')
assert.equal(dashboardHashRouteForPath('/solonclaw/models'), '#/solonclaw/models')
assert.equal(dashboardHashRouteForPath('/unknown'), '')
