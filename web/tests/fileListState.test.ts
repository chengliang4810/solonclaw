import assert from 'node:assert/strict'
import { fileListLoadFailedState } from '../src/shared/fileListState.ts'

const state = fileListLoadFailedState(new Error('network down'))

assert.deepEqual(state.entries, [])
assert.equal(state.error, 'network down')
