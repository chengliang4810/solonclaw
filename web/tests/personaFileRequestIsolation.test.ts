import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

interface PersonaResult {
  key: string
  content: string
}

interface PersonaMocks {
  fetch: (key: string) => Promise<PersonaResult>
  save: (key: string, content: string) => Promise<void>
  successes: string[]
  errors: string[]
}

declare global {
  var __PERSONA_FILE_MOCKS__: PersonaMocks
}

/** 等待 Vue watcher 与异步 continuation 完成。 */
async function flush(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
}

const source = readFileSync(new URL('../src/views/solonclaw/PersonaFileView.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)?.[1]
assert.ok(script, 'PersonaFileView should contain a TypeScript setup script')

const testsDirectory = dirname(fileURLToPath(import.meta.url))
const temporaryDirectory = mkdtempSync(join(testsDirectory, '.tmp-persona-file-'))
const modulePath = join(temporaryDirectory, 'persona-file-test-module.ts')

try {
  const transformed = script
    .replace(/^import .*$/gm, '')
    .replace('const route = useRoute()', "export const route = reactive({ params: { key: 'agents' } })")
    .replace("const { t } = useI18n()", "const t = (key: string) => key")
    .replace(
      "const loading = ref(false)",
      `type PersonaFileData = PersonaResult
let unmountCallback = () => {}
const onUnmounted = (callback: () => void) => { unmountCallback = callback }
const fetchPersonaFile = (key: string) => globalThis.__PERSONA_FILE_MOCKS__.fetch(key)
const savePersonaFile = (key: string, content: string) => globalThis.__PERSONA_FILE_MOCKS__.save(key, content)
const personaMeta = (key: string) => ({ title: key, description: key, fileName: key })
const message = {
  success: (value: string) => globalThis.__PERSONA_FILE_MOCKS__.successes.push(value),
  error: (value: string) => globalThis.__PERSONA_FILE_MOCKS__.errors.push(value),
}
const loading = ref(false)`,
    )
    .replace('onMounted(loadFile)', '')
    .concat('\nexport const simulateUnmount = () => unmountCallback()\n')
    .concat('export { file, fileKey, editing, editContent, loading, saving, loadFile, startEdit, handleSave }\n')
  writeFileSync(
    modulePath,
    `import { computed, reactive, ref, watch } from 'vue'\ninterface PersonaResult { key: string; content: string }\n${transformed}`,
  )

  let resolveAgents!: (value: PersonaResult) => void
  let resolveSoul!: (value: PersonaResult) => void
  const fetchKeys: string[] = []
  globalThis.__PERSONA_FILE_MOCKS__ = {
    fetch: key => new Promise(resolve => {
      fetchKeys.push(key)
      if (key === 'agents') resolveAgents = resolve
      else resolveSoul = resolve
    }),
    save: async () => {},
    successes: [],
    errors: [],
  }

  const view = await import(pathToFileURL(modulePath).href)
  const firstLoad = view.loadFile()
  view.route.params.key = 'soul'
  await flush()
  assert.equal(view.file.value, null, 'switching files should clear old content immediately')
  assert.equal(view.editContent.value, '', 'switching files should clear the old draft immediately')

  resolveSoul({ key: 'soul', content: 'soul-content' })
  await flush()
  assert.equal(view.file.value?.key, 'soul', 'current file response should populate the page')
  resolveAgents({ key: 'agents', content: 'late-agents-content' })
  await firstLoad
  assert.equal(view.file.value?.key, 'soul', 'late old-file response must be discarded')
  assert.equal(view.editContent.value, 'soul-content', 'late old-file content must not replace the current draft')

  view.startEdit()
  view.editContent.value = 'edited-soul'
  view.route.params.key = 'agents'
  await view.handleSave()
  assert.equal(globalThis.__PERSONA_FILE_MOCKS__.successes.length, 0, 'stale editor must not report a successful save')

  await flush()
  resolveAgents({ key: 'agents', content: 'agents-content' })
  await flush()
  view.startEdit()
  view.editContent.value = 'new-agents-content'
  let resolveSave!: () => void
  const saves: Array<{ key: string, content: string }> = []
  globalThis.__PERSONA_FILE_MOCKS__.save = (key, content) => new Promise(resolve => {
    saves.push({ key, content })
    resolveSave = resolve
  })
  const pendingSave = view.handleSave()
  view.route.params.key = 'soul'
  await flush()
  resolveSave()
  await pendingSave
  assert.deepEqual(saves, [{ key: 'agents', content: 'new-agents-content' }], 'save must use its captured file and content')
  assert.equal(globalThis.__PERSONA_FILE_MOCKS__.successes.length, 0, 'late save completion must not affect the new page')

  resolveSoul({ key: 'soul', content: 'current-soul-content' })
  await flush()
  view.startEdit()
  view.editContent.value = 'saved-before-unmount'
  globalThis.__PERSONA_FILE_MOCKS__.save = (key, content) => new Promise(resolve => {
    saves.push({ key, content })
    resolveSave = resolve
  })
  const fetchCountBeforeUnmount = fetchKeys.length
  const unmountedSave = view.handleSave()
  view.simulateUnmount()
  resolveSave()
  await unmountedSave
  assert.equal(fetchKeys.length, fetchCountBeforeUnmount, 'unmounted save completion must not refetch through the new Profile context')
  assert.equal(globalThis.__PERSONA_FILE_MOCKS__.successes.length, 0, 'unmounted save completion must not show a success toast')
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true })
  delete globalThis.__PERSONA_FILE_MOCKS__
}
