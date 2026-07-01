import assert from 'node:assert/strict'
import { dashboardHashRouteForPath } from '../src/shared/dashboardDirectRoutes.ts'

assert.equal(dashboardHashRouteForPath('/logs'), '#/solonclaw/logs')
assert.equal(dashboardHashRouteForPath('/channels'), '#/solonclaw/channels')
assert.equal(dashboardHashRouteForPath('/cron'), '#/solonclaw/jobs')
assert.equal(dashboardHashRouteForPath('/config'), '#/solonclaw/settings')
assert.equal(dashboardHashRouteForPath('/solonclaw/models'), '#/solonclaw/models')
assert.equal(dashboardHashRouteForPath('/unknown'), '')
