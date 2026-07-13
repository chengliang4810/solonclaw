import { Box, Text, useInput, wrapAnsi } from '@solonclaw/ink'
import { useState } from 'react'

import { isMac } from '../lib/platform.js'
import type { Theme } from '../theme.js'
import type { ApprovalReq, ClarifyReq, ConfirmReq } from '../types.js'

import { TextInput } from './textInput.js'

const OPTS = ['once', 'session', 'always', 'deny'] as const
const LABELS = { always: 'Always allow', deny: 'Deny', once: 'Allow once', session: 'Allow this session' } as const
const CMD_PREVIEW_LINES = 10
type ApprovalChoice = (typeof OPTS)[number]
const MEMORY_OPTS: readonly ApprovalChoice[] = ['once', 'deny']

const approvalOptions = (allowPermanent: boolean, approvalKind?: string): readonly ApprovalChoice[] =>
  approvalKind === 'memory' ? MEMORY_OPTS : allowPermanent ? OPTS : OPTS.filter(option => option !== 'always')

type ApprovalKey = {
  downArrow?: boolean
  escape?: boolean
  return?: boolean
  upArrow?: boolean
}

type ApprovalAction =
  | { kind: 'buffer'; value: string }
  | { approvalId?: string; kind: 'choose'; choice: ApprovalChoice }
  | { kind: 'move'; delta: -1 | 1 }
  | { kind: 'noop' }

const ESC = String.fromCharCode(27)
const BRACKETED_PASTE_END = new RegExp(`${ESC}\\[201~`, 'g')
const BRACKETED_PASTE_START = new RegExp(`${ESC}\\[200~`, 'g')

const stripBracketedPaste = (text: string) => text.replace(BRACKETED_PASTE_START, '').replace(BRACKETED_PASTE_END, '')

type ParsedApprovalCommand = {
  readonly approvalId?: string
  readonly choice: ApprovalChoice
}

const APPROVAL_SCOPE_WORDS = new Set(['always', 'deny', 'once', 'session'])

const parsedApprovalCommand = (choice: ApprovalChoice, approvalId?: string): ParsedApprovalCommand =>
  approvalId ? { approvalId, choice } : { choice }

export function approvalChoiceFromTextCommand(text: string): null | ParsedApprovalCommand {
  const cleaned = stripBracketedPaste(text).trim().replace(/\s+/g, ' ')

  if (!cleaned) {
    return null
  }

  const [head = '', ...rest] = cleaned.startsWith('/') ? cleaned.slice(1).split(' ') : cleaned.split(' ')
  const command = head.toLowerCase()
  const args = rest.map(part => part.toLowerCase())

  const approvalId = rest.find((_part, index) => {
    const normalized = args[index]

    return normalized ? !APPROVAL_SCOPE_WORDS.has(normalized) : false
  })

  if (command === 'deny') {
    return parsedApprovalCommand('deny', approvalId)
  }

  if (command !== 'approve') {
    return null
  }

  if (args.includes('always')) {
    return parsedApprovalCommand('always', approvalId)
  }

  if (args.includes('session')) {
    return parsedApprovalCommand('session', approvalId)
  }

  return parsedApprovalCommand('once', approvalId)
}

/**
 * Pure key-dispatch for the approval prompt — exported so the regression
 * matrix (Esc, Ctrl+C-equivalent, number keys, Enter, ↑↓) is testable
 * without mounting React + Ink + a fake stdin.  The component just maps the
 * action onto its own state setters.
 *
 * Esc and number keys both terminate the prompt; Esc maps to deny (parity
 * with the global Ctrl+C handler that already calls cancelOverlayFromCtrlC
 * for approvals).  Numbers 1..OPTS.length pick the labelled choice.  Enter
 * confirms the current selection.  ↑/↓ moves the selection within bounds.
 */
export function approvalAction(
  ch: string,
  key: ApprovalKey,
  sel: number,
  bufferedText = '',
  allowPermanent = true,
  approvalKind?: string
): ApprovalAction {
  if (key.escape) {
    return { kind: 'choose', choice: 'deny' }
  }

  const options = approvalOptions(allowPermanent, approvalKind)
  const input = stripBracketedPaste(ch)
  const activeBuffer = bufferedText.trim() || input.startsWith('/') ? bufferedText : ''

  if (key.return && activeBuffer) {
    const parsed = approvalChoiceFromTextCommand(activeBuffer)

    return parsed && options.includes(parsed.choice) ? { ...parsed, kind: 'choose' } : { kind: 'buffer', value: '' }
  }

  if (input.length > 1) {
    const parsed = approvalChoiceFromTextCommand(input)

    return parsed && options.includes(parsed.choice) ? { ...parsed, kind: 'choose' } : { kind: 'noop' }
  }

  if (activeBuffer || input === '/') {
    return input ? { kind: 'buffer', value: activeBuffer + input } : { kind: 'noop' }
  }

  const n = /^[1-4]$/.test(input) ? parseInt(input, 10) : Number.NaN

  if (n >= 1 && n <= options.length) {
    return { kind: 'choose', choice: options[n - 1]! }
  }

  if (key.return) {
    return { kind: 'choose', choice: options[Math.min(Math.max(sel, 0), options.length - 1)]! }
  }

  if (key.upArrow && sel > 0) {
    return { kind: 'move', delta: -1 }
  }

  if (key.downArrow && sel < options.length - 1) {
    return { kind: 'move', delta: 1 }
  }

  return { kind: 'noop' }
}

export function ApprovalPrompt({ cols = 80, onChoice, req, t }: ApprovalPromptProps) {
  const [sel, setSel] = useState(0)
  const [typedApproval, setTypedApproval] = useState('')
  const options = approvalOptions(req.allowPermanent !== false, req.approvalKind)

  useInput((ch, key) => {
    const action = approvalAction(ch, key, sel, typedApproval, req.allowPermanent !== false, req.approvalKind)

    if (action.kind === 'choose') {
      setTypedApproval('')
      onChoice(action.choice, action.approvalId)
    } else if (action.kind === 'move') {
      setSel(s => s + action.delta)
    } else if (action.kind === 'buffer') {
      setTypedApproval(action.value)
    }
  })

  const innerWidth = Math.max(20, cols - 8)

  const rawLines = req.command
    .split('\n')
    .flatMap(line => wrapAnsi(line, innerWidth, { hard: true, trim: false }).split('\n'))

  const shown = rawLines.slice(0, CMD_PREVIEW_LINES)
  const overflow = rawLines.length - shown.length

  return (
    <Box borderColor={t.color.warn} borderStyle="double" flexDirection="column" paddingX={1} width="100%">
      <Text bold color={t.color.warn}>
        ⚠ approval required · {req.description}
      </Text>

      <Box flexDirection="column" paddingLeft={1}>
        {shown.map((line, i) => (
          <Text color={t.color.text} key={i} wrap="wrap">
            {line || ' '}
          </Text>
        ))}

        {overflow > 0 ? (
          <Text color={t.color.muted}>
            … +{overflow} more line{overflow === 1 ? '' : 's'} (full text above)
          </Text>
        ) : null}
      </Box>

      <Text />

      {options.map((o, i) => (
        <Text key={o}>
          <Text bold={sel === i} color={sel === i ? t.color.warn : t.color.muted} inverse={sel === i}>
            {sel === i ? '▸ ' : '  '}
            {i + 1}. {LABELS[o]}
          </Text>
        </Text>
      ))}

      <Text color={t.color.muted}>↑/↓ select · Enter confirm · 1-{options.length} quick pick · Esc/Ctrl+C deny</Text>
    </Box>
  )
}

export function ClarifyPrompt({ cols = 80, onAnswer, onCancel, req, t }: ClarifyPromptProps) {
  const [sel, setSel] = useState(0)
  const [custom, setCustom] = useState('')
  const [typing, setTyping] = useState(false)
  const choices = req.choices ?? []

  const heading = (
    <Text bold>
      <Text color={t.color.accent}>ask</Text>
      <Text color={t.color.text}> {req.question}</Text>
    </Text>
  )

  useInput((ch, key) => {
    if (key.escape) {
      typing && choices.length ? setTyping(false) : onCancel()

      return
    }

    if (typing || !choices.length) {
      return
    }

    if (key.upArrow && sel > 0) {
      setSel(s => s - 1)
    }

    if (key.downArrow && sel < choices.length) {
      setSel(s => s + 1)
    }

    if (key.return) {
      sel === choices.length ? setTyping(true) : choices[sel] && onAnswer(choices[sel]!)
    }

    const n = parseInt(ch)

    if (n >= 1 && n <= choices.length) {
      onAnswer(choices[n - 1]!)
    }
  })

  if (typing || !choices.length) {
    return (
      <Box flexDirection="column">
        {heading}

        <Box>
          <Text color={t.color.label}>{'> '}</Text>
          <TextInput columns={Math.max(20, cols - 6)} onChange={setCustom} onSubmit={onAnswer} value={custom} />
        </Box>

        <Text color={t.color.muted}>
          Enter send · Esc {choices.length ? 'back' : 'cancel'} ·{' '}
          {isMac ? 'Cmd+C copy · Cmd+V paste · Ctrl+C cancel' : 'Ctrl+C cancel'}
        </Text>
      </Box>
    )
  }

  return (
    <Box flexDirection="column">
      {heading}

      {[...choices, 'Other (type your answer)'].map((c, i) => (
        <Text key={i}>
          <Text bold={sel === i} color={sel === i ? t.color.label : t.color.muted} inverse={sel === i}>
            {sel === i ? '▸ ' : '  '}
            {i + 1}. {c}
          </Text>
        </Text>
      ))}

      <Text color={t.color.muted}>↑/↓ select · Enter confirm · 1-{choices.length} quick pick · Esc/Ctrl+C cancel</Text>
    </Box>
  )
}

export function ConfirmPrompt({ onCancel, onConfirm, req, t }: ConfirmPromptProps) {
  const [sel, setSel] = useState(0)

  useInput((ch, key) => {
    const lower = ch.toLowerCase()

    if (key.escape || (key.ctrl && lower === 'c') || lower === 'n') {
      return onCancel()
    }

    if (lower === 'y') {
      return onConfirm()
    }

    if (key.upArrow) {
      setSel(0)
    }

    if (key.downArrow) {
      setSel(1)
    }

    if (key.return) {
      sel === 0 ? onCancel() : onConfirm()
    }
  })

  const accent = req.danger ? t.color.error : t.color.warn

  const rows = [
    { color: t.color.text, label: req.cancelLabel ?? 'No' },
    { color: req.danger ? t.color.error : t.color.text, label: req.confirmLabel ?? 'Yes' }
  ]

  return (
    <Box borderColor={accent} borderStyle="double" flexDirection="column" paddingX={1}>
      <Text bold color={accent}>
        {req.danger ? '⚠' : '?'} {req.title}
      </Text>

      {req.detail ? (
        <Box paddingLeft={1}>
          <Text color={t.color.text} wrap="truncate-end">
            {req.detail}
          </Text>
        </Box>
      ) : null}

      <Text />

      {rows.map((row, i) => (
        <Text key={row.label}>
          <Text color={sel === i ? accent : t.color.muted}>{sel === i ? '▸ ' : '  '}</Text>
          <Text color={sel === i ? row.color : t.color.muted}>{row.label}</Text>
        </Text>
      ))}

      <Text color={t.color.muted}>↑/↓ select · Enter confirm · Y/N quick · Esc cancel</Text>
    </Box>
  )
}

interface ApprovalPromptProps {
  cols?: number
  onChoice: (s: string, approvalId?: string) => void
  req: ApprovalReq
  t: Theme
}

interface ClarifyPromptProps {
  cols?: number
  onAnswer: (s: string) => void
  onCancel: () => void
  req: ClarifyReq
  t: Theme
}

interface ConfirmPromptProps {
  onCancel: () => void
  onConfirm: () => void
  req: ConfirmReq
  t: Theme
}
