export function logError(error: unknown): void {
  if (!process.env.SOLONCLAW_INK_DEBUG_ERRORS) {
    return
  }

  console.error(error)
}
