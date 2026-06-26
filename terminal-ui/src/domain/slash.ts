/** Appended to `/model` args from the TUI picker for session scope; stripped in `session` slash before `config.set`. */
export const TUI_SESSION_MODEL_FLAG = '--tui-session'

export const looksLikeSlashCommand = (text: string) => /^\/[^\s/]*(?:\s|$)/.test(text)

export const parseSlashCommand = (cmd: string) => {
  const [name = '', ...rest] = cmd.slice(1).split(/\s+/)

  return { arg: rest.join(' '), cmd, name: name.toLowerCase() }
}

export const applyCompletion = (value: string, rowText: string, compReplace: number): string => {
  const text = value.startsWith('/') && rowText.startsWith('/') ? rowText.slice(1) : rowText

  return value.slice(0, compReplace) + text
}

export const completionToApplyOnSubmit = (
  value: string,
  rowText: string | undefined,
  compReplace: number
): string | null => {
  if (!rowText) {
    return null
  }

  const next = applyCompletion(value, rowText, compReplace)

  return next !== value && next.trimEnd() !== value.trimEnd() ? next : null
}
