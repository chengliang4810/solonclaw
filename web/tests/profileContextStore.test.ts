import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createPinia, setActivePinia } from 'pinia'
import { createServer, type Plugin } from 'vite'

interface FilesMock {
  listFiles: (path: string) => Promise<{ path: string, entries: Array<Record<string, unknown>> }>
  readFile: (path: string) => Promise<{ content: string }>
  writeFile: (path: string, content: string) => Promise<void>
}

interface JobsMock {
  listJobs: () => Promise<Array<Record<string, unknown>>>
  runJob: (id: string) => Promise<Record<string, unknown>>
}

interface ProfilesMock {
  fetchProfiles: () => Promise<{
    profiles: Array<{ name: string }>
    active: string
    current: string
  }>
  setActiveProfile: (name: string) => Promise<{ active: string, current: string }>
}

declare global {
  var __PROFILE_FILES_MOCK__: FilesMock
  var __PROFILE_JOBS_MOCK__: JobsMock
  var __PROFILE_PROFILES_MOCK__: ProfilesMock
}

/** 为文件和 Cron Store 提供可控 API 响应。 */
function profileApiMocks(): Plugin {
  const modules: Record<string, string> = {
    '@/api/solonclaw/files': `
      export function listFiles(path = '') { return globalThis.__PROFILE_FILES_MOCK__.listFiles(path) }
      export function readFile(path) { return globalThis.__PROFILE_FILES_MOCK__.readFile(path) }
      export function writeFile(path, content) { return globalThis.__PROFILE_FILES_MOCK__.writeFile(path, content) }
      export async function restoreFile(path) { return { name: path, content: '' } }
    `,
    '@/api/solonclaw/jobs': `
      export function listJobs() { return globalThis.__PROFILE_JOBS_MOCK__.listJobs() }
      export async function listUpcomingJobs() { return [] }
      export async function fetchCronStatus() { return null }
      export async function fetchCronGuide() { return {} }
      export async function fetchCronPolicy() { return {} }
      export async function createJob(data) { return data }
      export async function updateJob(id, data) { return { ...data, job_id: id } }
      export async function deleteJob() {}
      export async function pauseJob(id) { return { job_id: id } }
      export async function resumeJob(id) { return { job_id: id } }
      export function runJob(id) { return globalThis.__PROFILE_JOBS_MOCK__.runJob(id) }
      export async function retryJob(id) { return { job_id: id } }
      export async function fetchJobRuns() { return [] }
      export async function getJob(id) { return { job_id: id } }
      export async function inspectJob(id) { return { job_id: id } }
    `,
    '@/api/client': `
      export function setManagementProfile() {}
    `,
    '@/api/solonclaw/profiles': `
      export function fetchProfiles() { return globalThis.__PROFILE_PROFILES_MOCK__.fetchProfiles() }
      export function setActiveProfile(name) { return globalThis.__PROFILE_PROFILES_MOCK__.setActiveProfile(name) }
    `,
  }

  return {
    name: 'profile-context-test-mocks',
    enforce: 'pre',
    resolveId(id) {
      const sourceId = Object.keys(modules).find(candidate =>
        candidate === id
        || id.endsWith(`/src/${candidate.slice(2)}`)
        || id.endsWith(`/src/${candidate.slice(2)}.ts`),
      )
      return sourceId ? `\0profile-test:${sourceId}` : null
    },
    load(id) {
      const sourceId = id.startsWith('\0profile-test:') ? id.slice('\0profile-test:'.length) : ''
      return modules[sourceId] ?? null
    },
  }
}

const webRoot = resolve(fileURLToPath(new URL('..', import.meta.url)))
const server = await createServer({
  root: webRoot,
  configFile: false,
  appType: 'custom',
  server: { middlewareMode: true },
  resolve: { alias: { '@': resolve(webRoot, 'src') } },
  plugins: [profileApiMocks()],
})

try {
  globalThis.__PROFILE_FILES_MOCK__ = {
    listFiles: async path => ({ path, entries: [{ name: 'a.txt', path: 'a.txt', isDir: false, size: 1, modTime: '' }] }),
    readFile: async () => ({ content: 'profile-a-content' }),
    writeFile: async () => {},
  }
  let runCalls = 0
  globalThis.__PROFILE_JOBS_MOCK__ = {
    listJobs: async () => [{ job_id: 'job-a', id: 'job-a' }],
    runJob: async id => {
      runCalls += 1
      return { job_id: id }
    },
  }
  globalThis.__PROFILE_PROFILES_MOCK__ = {
    fetchProfiles: async () => ({
      profiles: [{ name: 'profile-a' }, { name: 'profile-b' }],
      active: 'profile-a',
      current: 'profile-a',
    }),
    setActiveProfile: async name => ({ active: name, current: name }),
  }

  const { currentProfileContextVersion, updateProfileContext } = await server.ssrLoadModule('/src/shared/profileContext.ts') as typeof import('../src/shared/profileContext.ts')
  const { useFilesStore } = await server.ssrLoadModule('/src/stores/solonclaw/files.ts') as typeof import('../src/stores/solonclaw/files.ts')
  const { useJobsStore } = await server.ssrLoadModule('/src/stores/solonclaw/jobs.ts') as typeof import('../src/stores/solonclaw/jobs.ts')
  const { useProfilesStore } = await server.ssrLoadModule('/src/stores/solonclaw/profiles.ts') as typeof import('../src/stores/solonclaw/profiles.ts')
  setActivePinia(createPinia())
  const filesStore = useFilesStore()
  const jobsStore = useJobsStore()
  const profilesStore = useProfilesStore()

  await Promise.all([filesStore.fetchEntries(''), jobsStore.fetchJobs()])
  await filesStore.openEditor('a.txt')
  assert.equal(filesStore.entries.length, 1, 'profile A files should load')
  assert.equal(filesStore.editingFile?.content, 'profile-a-content', 'profile A editor should load')
  assert.equal(jobsStore.jobs[0]?.job_id, 'job-a', 'profile A jobs should load')

  let resolveFiles!: (value: { path: string, entries: Array<Record<string, unknown>> }) => void
  let resolveJobs!: (value: Array<Record<string, unknown>>) => void
  globalThis.__PROFILE_FILES_MOCK__.listFiles = () => new Promise(resolve => { resolveFiles = resolve })
  globalThis.__PROFILE_JOBS_MOCK__.listJobs = () => new Promise(resolve => { resolveJobs = resolve })
  const lateFiles = filesStore.fetchEntries('')
  const lateJobs = jobsStore.fetchJobs()

  updateProfileContext('profile-b')
  assert.equal(filesStore.entries.length, 0, 'profile switch should clear file entries synchronously')
  assert.equal(filesStore.editingFile, null, 'profile switch should close the old editor synchronously')
  assert.equal(jobsStore.jobs.length, 0, 'profile switch should clear old jobs synchronously')
  await filesStore.saveEditor()

  resolveFiles({ path: '', entries: [{ name: 'late-a.txt', path: 'late-a.txt' }] })
  resolveJobs([{ job_id: 'late-job-a', id: 'late-job-a' }])
  await Promise.all([lateFiles, lateJobs])
  assert.equal(filesStore.entries.length, 0, 'late profile A file response must be discarded')
  assert.equal(jobsStore.jobs.length, 0, 'late profile A jobs response must be discarded')

  await assert.rejects(
    jobsStore.runJob('job-a'),
    /current profile/,
    'old job ids must not be sent to the new profile',
  )
  assert.equal(runCalls, 0, 'rejected old job ids must not reach the API')

  const initialVersion = currentProfileContextVersion()
  await profilesStore.fetchProfiles()
  assert.equal(profilesStore.managedProfileName, 'profile-a', 'server current profile should become the managed profile')
  assert.ok(currentProfileContextVersion() > initialVersion, 'server current profile should publish a new context generation')
  const appSource = readFileSync(resolve(webRoot, 'src/App.vue'), 'utf8')
  assert.match(
    appSource,
    /<router-view :key="profilesStore\.managedProfileName" \/>/,
    'server current profile changes should remount the active route',
  )

  let resolveActiveProfile!: (value: { active: string, current: string }) => void
  globalThis.__PROFILE_PROFILES_MOCK__.setActiveProfile = () => new Promise(resolve => {
    resolveActiveProfile = resolve
  })
  const lateActiveProfile = profilesStore.setActiveProfile('profile-a')
  profilesStore.setManagementProfile('profile-b')
  resolveActiveProfile({ active: 'profile-a', current: 'profile-a' })
  await lateActiveProfile
  assert.equal(
    profilesStore.managedProfileName,
    'profile-b',
    'late active-profile response must not override a newer management selection',
  )

  const guardedStores = ['app.ts', 'chat.ts', 'files.ts', 'gateways.ts', 'jobs.ts', 'models.ts', 'settings.ts']
  for (const file of guardedStores) {
    const source = readFileSync(resolve(webRoot, 'src/stores/solonclaw', file), 'utf8')
    assert.match(source, /ProfileContext/, `${file} should invalidate Profile-scoped state`)
  }
} finally {
  await server.close()
  delete globalThis.__PROFILE_FILES_MOCK__
  delete globalThis.__PROFILE_JOBS_MOCK__
  delete globalThis.__PROFILE_PROFILES_MOCK__
}
