import { mkdtemp, readdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('node:v8', () => ({
  getHeapSnapshot: vi.fn(() => Readable.from(['heap'])),
  getHeapSpaceStatistics: vi.fn(() => []),
  getHeapStatistics: vi.fn(() => ({
    heap_size_limit: 1024,
    malloced_memory: 0,
    native_contexts: 0,
    number_of_detached_contexts: 0,
    peak_malloced_memory: 0
  }))
}))

import { performHeapDump } from '../lib/memory.js'

describe('performHeapDump', () => {
  let dir = ''
  const oldDir = process.env.SOLONCLAW_HEAPDUMP_DIR
  const oldAuto = process.env.SOLONCLAW_AUTO_HEAPDUMP

  beforeEach(async () => {
    dir = await mkdtemp(join(tmpdir(), 'solonclaw-heap-'))
    process.env.SOLONCLAW_HEAPDUMP_DIR = dir
    delete process.env.SOLONCLAW_AUTO_HEAPDUMP
  })

  afterEach(async () => {
    process.env.SOLONCLAW_HEAPDUMP_DIR = oldDir
    process.env.SOLONCLAW_AUTO_HEAPDUMP = oldAuto
    await rm(dir, { force: true, recursive: true })
  })

  it('suppresses heavy snapshots for automatic triggers unless enabled', async () => {
    const result = await performHeapDump('auto-high')
    const files = await readdir(dir)

    expect(result.success).toBe(true)
    expect(result.suppressed).toBe(true)
    expect(result.diagPath).toBeDefined()
    expect(result.heapPath).toBeUndefined()
    expect(files.some(name => name.endsWith('.diagnostics.json'))).toBe(true)
    expect(files.some(name => name.endsWith('.heapsnapshot'))).toBe(false)
  })
})
