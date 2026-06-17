import assert from 'node:assert/strict'
import {
  asArray,
  displayJson,
  hasItems,
  hasText,
  isRecord,
  joinTextList,
  listCount,
  splitTrimmedText,
  trimText,
} from '../src/shared/text.ts'

assert.equal(trimText('  会话  '), '会话')
assert.equal(trimText(null), '')
assert.equal(hasText('  '), false)
assert.equal(hasText(' 模型 '), true)
assert.deepEqual(splitTrimmedText('a, b,,c', ','), ['a', 'b', 'c'])
assert.deepEqual(splitTrimmedText('a\nb，c', /[\r\n,，]+/), ['a', 'b', 'c'])
assert.deepEqual(asArray<string>(['x']), ['x'])
assert.deepEqual(asArray<string>('x'), [])
assert.equal(hasItems(['x']), true)
assert.equal(hasItems([]), false)
assert.equal(listCount(['x', 'y']), 2)
assert.equal(listCount({ length: 2 }), 0)
assert.equal(joinTextList(['a', 'b']), 'a, b')
assert.equal(joinTextList(null), '')
assert.equal(isRecord({ a: 1 }), true)
assert.equal(isRecord(['a']), false)
assert.equal(displayJson({ a: 1 }), '{\n  "a": 1\n}')
assert.equal(displayJson('', { emptyText: '' }), '')
