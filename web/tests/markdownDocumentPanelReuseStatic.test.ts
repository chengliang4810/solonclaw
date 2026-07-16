import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'

const sharedPanelFile = new URL('../src/components/solonclaw/markdown/MarkdownDocumentPanel.vue', import.meta.url)
const personaFileViewFile = new URL('../src/views/solonclaw/PersonaFileView.vue', import.meta.url)

assert.ok(existsSync(sharedPanelFile), 'Markdown document pages should use one shared editor panel component')

const sharedPanel = readFileSync(sharedPanelFile, 'utf8')
const personaFileView = readFileSync(personaFileViewFile, 'utf8')

assert.ok(personaFileView.includes('MarkdownDocumentPanel'), 'PersonaFileView should delegate document preview/edit UI')
assert.equal(
  (personaFileView.match(/<MarkdownDocumentPanel/g) || []).length,
  1,
  'PersonaFileView should render exactly one shared document panel',
)
assert.ok(sharedPanel.includes('MarkdownRenderer'), 'Shared panel should keep Markdown preview rendering')
assert.ok(sharedPanel.includes('v-model="draft"'), 'Shared panel should own textarea draft binding')
assert.ok(sharedPanel.includes("emit('save'"), 'Shared panel should emit save requests')
assert.ok(sharedPanel.includes("emit('cancel'"), 'Shared panel should emit cancel requests')
assert.ok(!personaFileView.includes('class="edit-textarea"'), 'PersonaFileView should not keep duplicated textarea markup')
assert.ok(personaFileView.includes('watch(fileKey, loadFile)'), 'PersonaFileView should keep route-driven loading')
