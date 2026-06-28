/** Appended to `/model` args from the TUI picker for session scope; stripped in `session` slash before `config.set`. */
export const TUI_SESSION_MODEL_FLAG = '--tui-session'

export const looksLikeSlashCommand = (text: string) => /^\/[^\s/]*(?:\s|$)/.test(text)

export const parseSlashCommand = (cmd: string) => {
  const [name = '', ...rest] = cmd.slice(1).split(/\s+/)

  return { arg: rest.join(' '), cmd, name: name.toLowerCase() }
}

export const applyCompletion = (value: string, rowText: string, compReplace: number): string => {
  const prefix = value.slice(0, compReplace)
  const text = prefix.endsWith('/') && rowText.startsWith('/') ? rowText.slice(1) : rowText

  return prefix + text
}

export const completionToApplyOnSubmit = (
  value: string,
  rowText: string | undefined,
  compReplace: number,
  candidateTexts: readonly string[] = []
): string | null => {
  if (!rowText) {
    return null
  }
  const normalizedValue = value.trimEnd()
  if (
    compReplace === 0
    && value.startsWith('/')
    && candidateTexts.some(text => text.trimEnd() === normalizedValue)
  ) {
    return null
  }
  if (compReplace === 0 && value.startsWith('/') && rowText.startsWith('/') && !rowText.startsWith(value.trim())) {
    return null
  }

  const next = applyCompletion(value, rowText, compReplace)
  const normalizedNext = next.trimEnd()

  return next !== value && normalizedNext !== normalizedValue ? next : null
}
