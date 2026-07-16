import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../src/api/solonclaw/pairing.ts', import.meta.url), 'utf8')
const control = readFileSync(new URL('../src/components/solonclaw/channels/PairingControl.vue', import.meta.url), 'utf8')
const view = readFileSync(new URL('../src/views/solonclaw/ChannelsView.vue', import.meta.url), 'utf8')

assert.ok(api.includes("'/api/gateway/pairing/claim-owner'"), 'personal binding should use the trusted owner-claim endpoint')
assert.ok(api.includes("'/api/gateway/pairing/primary'"), 'personal binding should expose the primary notification channel endpoint')
assert.ok(api.includes("'/api/gateway/pairing/welcome/retry'"), 'personal binding should expose the trusted welcome retry endpoint')
assert.ok(api.includes("'/api/status'"), 'the binding view should read the real channel connection state')
assert.ok(api.includes('home_channel?: PairingHomeChannel'), 'pairing state should represent the platform default notification DM')
assert.ok(api.includes('primary?: boolean'), 'the default notification DM should report primary state')
assert.ok(control.includes('claimPairingOwner'), 'the channel binding control should claim the owner instead of approving shared users')
assert.ok(control.includes('profileName'), 'the channel binding control should identify the current Profile as the bot account')
assert.ok(control.includes('defaultNotificationDm'), 'the channel binding control should display the platform notification DM state')
assert.ok(control.includes('setPrimaryNotificationChannel'), 'a non-primary bound platform should offer the primary notification action')
assert.ok(control.includes('primaryNotificationChannel'), 'the primary platform should have a visible status label')
assert.ok(control.includes('retryPairingWelcome'), 'failed welcome delivery should offer a retry action')
assert.ok(control.includes('canRetryWelcome'), 'a bound owner should always be able to resend the welcome message')
assert.ok(control.includes('watch(() => props.profileName'), 'switching the managed Profile should refresh pairing state')
assert.equal(control.includes('revokePairing'), false, 'personal assistant binding must not expose shared-user revoke controls')
assert.equal(control.includes('setPairingAdmin'), false, 'personal assistant binding must not accept manually entered administrator identities')
assert.ok(view.includes('profilesStore.managedProfileName'), 'the channels page should pass the managed Profile to the binding control')
