import assert from 'node:assert/strict'

import { workspaceFileEntries } from '../src/shared/workspaceFileEntries.ts'

const files = [
  {
    key: 'agents',
    name: 'AGENTS.md',
    path: 'workspace://files/agents',
    exists: true,
    content: 'rules',
  },
  {
    key: 'memory_today',
    name: 'memory/2026-06-30.md',
    path: 'workspace://files/memory_today',
    exists: true,
    content: 'today',
  },
]

const root = workspaceFileEntries(files, '')
assert.deepEqual(
  root.entries.map(entry => ({ isDir: entry.isDir, name: entry.name, path: entry.path })),
  [
    { isDir: true, name: 'memory', path: 'memory' },
    { isDir: false, name: 'AGENTS.md', path: 'AGENTS.md' },
  ],
  'root file listing should expose nested workspace files as directories',
)

const memory = workspaceFileEntries(files, 'memory')
assert.deepEqual(
  memory.entries.map(entry => ({ isDir: entry.isDir, name: entry.name, path: entry.path, size: entry.size })),
  [
    { isDir: false, name: '2026-06-30.md', path: 'memory/2026-06-30.md', size: 5 },
  ],
  'subdirectory file listing should show direct child workspace files',
)
