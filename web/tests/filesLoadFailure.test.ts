import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createSSRApp, defineComponent } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createPinia, setActivePinia } from 'pinia'
import { compileTemplate, parse } from 'vue/compiler-sfc'

const testsDir = dirname(fileURLToPath(import.meta.url))
const tempDir = mkdtempSync(join(testsDir, '.tmp-files-load-failure-'))
const originalConsoleError = console.error

try {
  const mockApiPath = join(tempDir, 'mock-files-api.ts')
  const fileTypesPath = join(tempDir, 'file-types.ts')
  const storePath = join(tempDir, 'files-store-under-test.ts')

  writeFileSync(mockApiPath, `
export interface FileEntry {
  name: string
  path: string
  isDir: boolean
  size: number
  modTime: string
}

type ListResult = { entries: FileEntry[]; path: string }
type QueuedResponse = (path: string) => Promise<ListResult>

const queuedResponses: QueuedResponse[] = []

export function queueListSuccess(entries: FileEntry[]): void {
  queuedResponses.push(async (path) => ({ entries, path }))
}

export function queueListFailure(message: string): void {
  queuedResponses.push(async () => {
    throw new Error(message)
  })
}

export async function listFiles(path = ''): Promise<ListResult> {
  const next = queuedResponses.shift()
  if (!next) throw new Error('No mock file-list response queued')
  return next(path)
}
`)

  writeFileSync(fileTypesPath, readFileSync(new URL('../src/shared/fileTypes.ts', import.meta.url), 'utf8'))
  const storeSource = readFileSync(new URL('../src/stores/solonclaw/files.ts', import.meta.url), 'utf8')
    .replace("import * as filesApi from '@/api/solonclaw/files'", "import * as filesApi from './mock-files-api.ts'")
    .replace("import type { FileEntry } from '@/api/solonclaw/files'", "import type { FileEntry } from './mock-files-api.ts'")
    .replace("} from '../../shared/fileTypes.ts'", "} from './file-types.ts'")
  writeFileSync(storePath, storeSource)

  const mockApi = await import(pathToFileURL(mockApiPath).href)
  const { useFilesStore } = await import(pathToFileURL(storePath).href)

  setActivePinia(createPinia())
  const filesStore = useFilesStore()

  const staleEntry = { name: 'stale.txt', path: 'stale.txt', isDir: false, size: 5, modTime: '2026-06-27T00:00:00Z' }
  mockApi.queueListSuccess([staleEntry])
  await filesStore.fetchEntries('workspace')

  assert.deepEqual(filesStore.entries, [staleEntry], 'Given an initial successful load, the store should contain entries')
  assert.equal(filesStore.currentPath, 'workspace', 'Given an initial successful navigation, the store should update the current path')

  mockApi.queueListFailure('workspace file API unavailable')
  console.error = (...args: unknown[]) => {
    if (args[0] === 'Failed to fetch files:') return
    originalConsoleError(...args)
  }
  await assert.rejects(() => filesStore.navigateTo('missing'), /workspace file API unavailable/)
  console.error = originalConsoleError

  assert.deepEqual(filesStore.entries, [staleEntry], 'When loading fails, stale entries should remain visible')
  assert.equal(filesStore.currentPath, 'workspace', 'When navigation fails, the current path should stay on the previous successful directory')
  assert.equal(filesStore.loadError, 'workspace file API unavailable', 'When loading fails, the error should remain visible')
  assert.equal(filesStore.loading, false, 'When loading fails, the loading flag should be reset')

  const freshEntry = { name: 'fresh.md', path: 'fresh.md', isDir: false, size: 9, modTime: '2026-06-27T01:00:00Z' }
  mockApi.queueListSuccess([freshEntry])
  await filesStore.fetchEntries()

  assert.deepEqual(filesStore.entries, [freshEntry], 'When a retry succeeds, entries should be replaced with fresh data')
  assert.equal(filesStore.loadError, null, 'When a retry starts and succeeds, the previous load error should be cleared')

  const fileListSource = readFileSync(new URL('../src/components/solonclaw/files/FileList.vue', import.meta.url), 'utf8')
  const { descriptor } = parse(fileListSource)
  const templateSource = descriptor.template?.content
  assert.ok(templateSource, 'FileList should have a renderable template')

  const compiled = compileTemplate({
    id: 'files-load-failure-test',
    filename: 'FileList.vue',
    source: templateSource,
    compilerOptions: { mode: 'function' },
  })
  assert.equal(compiled.errors.length, 0, 'FileList template should compile for behavior verification')

  const render = new Function('Vue', compiled.code)(await import('vue'))
  const html = await renderToString(createSSRApp(defineComponent({
    components: {
      Spin: { template: '<div><slot /></div>' },
      Empty: { props: ['description'], template: '<div class="empty">{{ description }}</div>' },
      Button: { template: '<button><slot /></button>' },
    },
    setup() {
      return {
        filesStore: {
          loading: false,
          loadError: 'workspace file API unavailable',
          sortedEntries: [staleEntry],
          sortBy: 'name',
          sortOrder: 'asc',
          setSort: () => {},
          openEditor: () => {},
        },
        t: (key: string) => {
          if (key === 'files.loadFailed') return 'Failed to load files'
          if (key === 'files.emptyDir') return 'Empty directory'
          return key
        },
        fileTypeIcon: () => 'file',
        formatFileSize: () => '5 B',
        formatTimestampText: () => '2026-06-27',
        handleDoubleClick: () => {},
        handleContextMenu: () => {},
        handleDownload: () => {},
        isTextFile: () => false,
      }
    },
    render,
  })))

  assert.match(html, /Failed to load files/, 'FileList should render the persistent load failure label')
  assert.match(html, /workspace file API unavailable/, 'FileList should render the persistent load failure detail')
  assert.match(html, /stale\.txt/, 'FileList should keep stale rows visible while loadError is visible')
  assert.doesNotMatch(html, /Empty directory/, 'FileList should not show the empty state while loadError is visible')

  for (const locale of ['zh', 'en', 'ja', 'ko', 'pt', 'fr', 'de', 'es']) {
    const messages = await import(`../src/i18n/locales/${locale}.ts`)
    assert.equal(typeof messages.default.files.loadFailed, 'string', `${locale} locale should define files.loadFailed`)
    assert.notEqual(messages.default.files.loadFailed.trim(), '', `${locale} locale files.loadFailed should not be empty`)
  }
} finally {
  console.error = originalConsoleError
  rmSync(tempDir, { recursive: true, force: true })
}
