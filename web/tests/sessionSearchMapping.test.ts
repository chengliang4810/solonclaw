import assert from 'node:assert/strict'
import { mapDashboardSearchResult } from '../src/shared/sessionSearch.ts'

const base = {
  id: 's1',
  source: 'local',
  model: 'gpt',
  title: null,
  preview: '',
  started_at: 1,
  ended_at: null,
  last_active: 1,
  message_count: 2,
  tool_call_count: 0,
  input_tokens: 3,
  output_tokens: 4,
  cache_read_tokens: 0,
  cache_write_tokens: 0,
  reasoning_tokens: 0,
  provider: null,
}

assert.deepEqual(
  mapDashboardSearchResult(
    {
      session_id: 's1',
      title: 'Found',
      match_preview: 'needle hit',
      summary: 'summary',
      updated_at: 1700000000000,
      branch_name: 'main',
      channel: 'web',
    },
    base,
    2,
  ),
  {
    ...base,
    title: 'Found',
    source: 'local',
    branch_name: 'main',
    last_active: 1700000000,
    started_at: 1700000000,
    matched_message_id: null,
    snippet: 'needle hit',
    rank: 2,
  },
)
