import assert from 'node:assert/strict'
import { nextPanelCursor, normalizePanelKey } from '../src/tui/panelNavigation.ts'

assert.equal(nextPanelCursor(0, 'down', 3), 1)
assert.equal(nextPanelCursor(2, 'down', 3), 0)
assert.equal(nextPanelCursor(0, 'up', 3), 2)
assert.equal(nextPanelCursor(1, 'home', 3), 0)
assert.equal(nextPanelCursor(1, 'end', 3), 2)
assert.equal(nextPanelCursor(2, 'down', 0), 0)
assert.equal(nextPanelCursor(4, 'up', 3), 2)

assert.equal(normalizePanelKey('ArrowDown'), 'down')
assert.equal(normalizePanelKey('ArrowUp'), 'up')
assert.equal(normalizePanelKey('Home'), 'home')
assert.equal(normalizePanelKey('End'), 'end')
assert.equal(normalizePanelKey('Enter'), 'select')
assert.equal(normalizePanelKey('Escape'), 'cancel')
assert.equal(normalizePanelKey('a'), 'none')
