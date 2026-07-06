export interface LoginUrlLocation {
  pathname: string
  search: string
  hash: string
}

export interface LoginUrlNormalization {
  token: string
  nextUrl?: string
}

export function normalizeLoginTokenUrl(
  location: LoginUrlLocation,
  directRouteForPath: (pathname: string) => string,
): LoginUrlNormalization {
  const urlParams = safeParams(location.search)
  const hash = location.hash || ''
  const hashQueryIndex = hash.indexOf('?')
  const hashRoutePath = hashQueryIndex >= 0 ? hash.slice(0, hashQueryIndex) : hash
  const directRouteHash = hashRoutePath ? '' : directRouteForPath(location.pathname)
  const hashQuery = hashQueryIndex >= 0 ? hash.slice(hashQueryIndex + 1) : ''
  const hashParams = hashQuery ? safeParams(hashQuery) : null
  const searchToken = safeGet(urlParams, 'token')
  const hashToken = hashParams ? safeGet(hashParams, 'token') : ''
  const token = searchToken || hashToken

  if (searchToken) {
    urlParams.delete('token')
  }
  if (hashToken && hashParams) {
    hashParams.delete('token')
  }
  if (!searchToken && !hashToken && !directRouteHash) {
    return { token }
  }

  const nextSearch = urlParams.toString()
  const nextHashQuery = hashParams?.toString() || ''
  const nextHash = `${hashRoutePath || directRouteHash || '#/'}${nextHashQuery ? `?${nextHashQuery}` : ''}`
  const nextPathname = directRouteHash ? '/' : location.pathname
  return {
    token,
    nextUrl: `${nextPathname}${nextSearch ? `?${nextSearch}` : ''}${nextHash}`,
  }
}

function safeParams(input: string): URLSearchParams {
  try {
    return new URLSearchParams(input)
  } catch {
    return new URLSearchParams()
  }
}

function safeGet(params: URLSearchParams, key: string): string {
  try {
    return params.get(key) || ''
  } catch {
    return ''
  }
}
