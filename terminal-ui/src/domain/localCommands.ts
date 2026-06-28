const LOCAL_CLI_COMMAND_RE =
  /^(?:auth(?:\s|$)|config(?:\s|$)|doctor(?:\s|$)|gateway(?:\s+setup(?:\s|$)|\s*$)|model(?:\s+set(?:\s|$)|\s+configure(?:\s|$))|pairing(?:\s|$)|setup(?:\s|$)|version(?:\s|$)|logout(?:\s|$)|status\s*$)/

export const looksLikeLocalCliCommand = (value: string): boolean =>
  LOCAL_CLI_COMMAND_RE.test(value.trim().toLowerCase())
