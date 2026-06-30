import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileTypeIcon } from '../src/shared/fileTypeIcon.ts'
import type { FileEntry } from '../src/api/solonclaw/files.ts'

const fileList = readFileSync(
  new URL('../src/components/solonclaw/files/FileList.vue', import.meta.url),
  'utf8',
)

function entry(name: string, isDir = false): FileEntry {
  return {
    name,
    path: name,
    isDir,
    size: 0,
    modTime: '',
  }
}

assert.equal(fileTypeIcon(entry('docs', true)), '📁')
assert.equal(fileTypeIcon(entry('config.yaml')), '⚙️')
assert.equal(fileTypeIcon(entry('package.json')), '📋')
assert.equal(fileTypeIcon(entry('README.md')), '📝')
assert.equal(fileTypeIcon(entry('agent.log')), '📄')
assert.equal(fileTypeIcon(entry('script.ts')), '📜')
assert.equal(fileTypeIcon(entry('App.vue')), '💚')
assert.equal(fileTypeIcon(entry('image.webp')), '🖼️')
assert.equal(fileTypeIcon(entry('archive.tar')), '📦')
assert.equal(fileTypeIcon(entry('deploy.sh')), '⚡')
assert.equal(fileTypeIcon(entry('unknown.bin')), '📄')

assert.ok(!fileList.includes('const iconMap'), 'FileList should not inline extension icon metadata')
assert.ok(fileList.includes("import { fileTypeIcon } from '@/shared/fileTypeIcon'"), 'FileList should reuse shared icon metadata')
assert.ok(fileList.includes('{{ fileTypeIcon(entry) }}'), 'FileList template should call the shared icon helper')
