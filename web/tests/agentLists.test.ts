import assert from 'node:assert/strict'
import {
  formatAgentListInput,
  parseAgentListInput,
  previewAgentListInput,
  serializeAgentListInput,
} from '../src/shared/agentLists.ts'

assert.deepEqual(parseAgentListInput(''), [])
assert.deepEqual(parseAgentListInput('read_file\nwrite_file'), ['read_file', 'write_file'])
assert.deepEqual(parseAgentListInput('java,review，ops'), ['java', 'review', 'ops'])
assert.deepEqual(parseAgentListInput('["read_file", "write_file"]'), ['read_file', 'write_file'])
assert.equal(formatAgentListInput('["java","review"]'), 'java\nreview')
assert.equal(serializeAgentListInput('read_file\nwrite_file'), '["read_file","write_file"]')
assert.deepEqual(previewAgentListInput('['), [])

assert.throws(
  () => parseAgentListInput('{"tool":"read_file"}'),
  /JSON array/,
)
