import { atom } from 'nanostores'

import type { SpawnTreeLoadResponse } from '../gatewayTypes.js'
import { normalizeSubagentStatus } from '../lib/subagentStatus.js'
import { asArray, asNumber, asOptionalArray, asOptionalString, asOptionalStringArray, asStringArray } from '../lib/value.js'
import type { SubagentProgress } from '../types.js'

export interface SpawnSnapshot {
  finishedAt: number
  fromDisk?: boolean
  id: string
  label: string
  path?: string
  sessionId: null | string
  startedAt: number
  subagents: SubagentProgress[]
}

export interface SpawnDiffPair {
  baseline: SpawnSnapshot
  candidate: SpawnSnapshot
}

const HISTORY_LIMIT = 10

export const $spawnHistory = atom<SpawnSnapshot[]>([])
export const $spawnDiff = atom<null | SpawnDiffPair>(null)

export const getSpawnHistory = () => $spawnHistory.get()
export const getSpawnDiff = () => $spawnDiff.get()

export const clearSpawnHistory = () => $spawnHistory.set([])
export const clearDiffPair = () => $spawnDiff.set(null)
export const setDiffPair = (pair: SpawnDiffPair) => $spawnDiff.set(pair)

/**
 * Commit a finished turn's spawn tree to history.  Keeps the last 10
 * non-empty snapshots — empty turns (no subagents) are dropped.
 *
 * Why in-memory?  The primary investigation loop is "I just ran a fan-out,
 * it misbehaved, let me look at what happened" — same-session debugging.
 * Disk persistence across process restarts is a natural extension but
 * adds RPC surface for a less-common path.
 */
export const pushSnapshot = (
  subagents: readonly SubagentProgress[],
  meta: { sessionId?: null | string; startedAt?: null | number }
) => {
  if (!subagents.length) {
    return
  }

  const now = Date.now()
  const started = meta.startedAt ?? Math.min(...subagents.map(s => s.startedAt ?? now))

  const snap: SpawnSnapshot = {
    finishedAt: now,
    id: `snap-${now.toString(36)}`,
    label: summarizeLabel(subagents),
    sessionId: meta.sessionId ?? null,
    startedAt: Number.isFinite(started) ? started : now,
    subagents: subagents.map(item => ({ ...item }))
  }

  const next = [snap, ...$spawnHistory.get()].slice(0, HISTORY_LIMIT)
  $spawnHistory.set(next)
}

function summarizeLabel(subagents: readonly SubagentProgress[]): string {
  const top = subagents
    .filter(s => s.parentId == null || subagents.every(o => o.id !== s.parentId))
    .slice(0, 2)
    .map(s => s.goal || 'subagent')
    .join(' · ')

  return top || `${subagents.length} agent${subagents.length === 1 ? '' : 's'}`
}

/**
 * 磁盘快照来自历史版本的网关载荷；这里只补齐 TUI 必需字段，可选字段缺省时继续保持 undefined，避免回放视图误判为空值是有效数据。
 */
export const pushDiskSnapshot = (r: SpawnTreeLoadResponse, path: string) => {
  const raw = asArray(r.subagents)
  const normalised = raw.map(normaliseSubagent)

  if (!normalised.length) {
    return
  }

  const snap: SpawnSnapshot = {
    finishedAt: (r.finished_at ?? Date.now() / 1000) * 1000,
    fromDisk: true,
    id: `disk-${path}`,
    label: r.label || `${normalised.length} subagents`,
    path,
    sessionId: r.session_id ?? null,
    startedAt: (r.started_at ?? r.finished_at ?? Date.now() / 1000) * 1000,
    subagents: normalised
  }

  const next = [snap, ...$spawnHistory.get()].slice(0, HISTORY_LIMIT)
  $spawnHistory.set(next)
}

function normaliseSubagent(raw: unknown): SubagentProgress {
  const o = raw as Record<string, unknown>

  return {
    apiCalls: asNumber(o.apiCalls),
    costUsd: asNumber(o.costUsd),
    depth: typeof o.depth === 'number' ? o.depth : 0,
    durationSeconds: asNumber(o.durationSeconds),
    filesRead: asOptionalStringArray(o.filesRead),
    filesWritten: asOptionalStringArray(o.filesWritten),
    goal: asOptionalString(o.goal) ?? 'subagent',
    id: asOptionalString(o.id) ?? `sa-${Math.random().toString(36).slice(2, 8)}`,
    index: typeof o.index === 'number' ? o.index : 0,
    inputTokens: asNumber(o.inputTokens),
    iteration: asNumber(o.iteration),
    model: asOptionalString(o.model),
    notes: asStringArray(o.notes),
    outputTail: asOptionalArray(o.outputTail) as SubagentProgress['outputTail'],
    outputTokens: asNumber(o.outputTokens),
    parentId: asOptionalString(o.parentId) ?? null,
    reasoningTokens: asNumber(o.reasoningTokens),
    startedAt: asNumber(o.startedAt),
    status: normalizeSubagentStatus(o.status, 'completed'),
    summary: asOptionalString(o.summary),
    taskCount: typeof o.taskCount === 'number' ? o.taskCount : 1,
    thinking: asStringArray(o.thinking),
    toolCount: typeof o.toolCount === 'number' ? o.toolCount : 0,
    tools: asStringArray(o.tools),
    toolsets: asOptionalStringArray(o.toolsets)
  }
}
