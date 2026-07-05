import assert from 'node:assert/strict'

import { normalizeLoginTokenUrl } from '../src/shared/loginUrlToken.ts'

const directRoute = (pathname: string) => pathname === '/chat' ? '#/solonclaw/chat' : ''

const search = normalizeLoginTokenUrl(
  { pathname: '/solonclaw', search: '?token=abc&keep=1', hash: '#/solonclaw/chat?x=1' },
  directRoute,
)

assert.equal(search.token, 'abc', 'normal query token should be captured')
assert.equal(
  search.nextUrl,
  '/solonclaw?keep=1#/solonclaw/chat?x=1',
  'normal query token should be removed while preserving remaining query and hash',
)

const rootSearch = normalizeLoginTokenUrl(
  { pathname: '/', search: '?token=root-token', hash: '#/solonclaw/chat' },
  directRoute,
)

assert.equal(rootSearch.token, 'root-token', 'root query token should be captured before hash routing starts')
assert.equal(
  rootSearch.nextUrl,
  '/#/solonclaw/chat',
  'root query token should be removed without losing the hash route',
)

const hash = normalizeLoginTokenUrl(
  { pathname: '/chat', search: '?keep=1', hash: '#/solonclaw/chat?token=hash-token&x=1' },
  directRoute,
)

assert.equal(hash.token, 'hash-token', 'hash route token should be captured')
assert.equal(
  hash.nextUrl,
  '/chat?keep=1#/solonclaw/chat?x=1',
  'hash route token should be removed without moving it into the normal query',
)

assert.doesNotThrow(
  () => normalizeLoginTokenUrl({ pathname: '/chat', search: '?token=%E0%A4%A', hash: '#/solonclaw/chat' }, directRoute),
  'malformed URL token input should not break app bootstrap',
)

const direct = normalizeLoginTokenUrl({ pathname: '/chat', search: '', hash: '' }, directRoute)
assert.equal(direct.token, '', 'direct route normalization should not invent a token')
assert.equal(direct.nextUrl, '/chat#/solonclaw/chat', 'direct dashboard paths should normalize before router starts')
