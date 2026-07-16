import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as jobsApi from '@/api/solonclaw/jobs'
import type { Job, CreateJobRequest, UpdateJobRequest, CronGuide, CronPolicy, CronStatus, JobInspectResult } from '@/api/solonclaw/jobs'
import { useProfileContextGuard } from '@/composables/useProfileContextGuard'

function matchId(job: Job, id: string): boolean {
  return job.job_id === id || job.id === id
}

function errorMessage(err: unknown, fallback: string): string {
  return err instanceof Error ? err.message : String(err || fallback)
}

export const useJobsStore = defineStore('jobs', () => {
  const jobs = ref<Job[]>([])
  const upcomingJobs = ref<Job[]>([])
  const status = ref<CronStatus | null>(null)
  const guide = ref<CronGuide | null>(null)
  const policy = ref<CronPolicy | null>(null)
  const loading = ref(false)
  const upcomingLoading = ref(false)
  const statusLoading = ref(false)
  const guideLoading = ref(false)
  const loadError = ref<string | null>(null)
  const upcomingError = ref<string | null>(null)
  const statusError = ref<string | null>(null)
  const guideError = ref<string | null>(null)

  /** 清空当前 Profile 的 Cron 数据和加载状态。 */
  function resetProfileState(): void {
    jobs.value = []
    upcomingJobs.value = []
    status.value = null
    guide.value = null
    policy.value = null
    loading.value = false
    upcomingLoading.value = false
    statusLoading.value = false
    guideLoading.value = false
    loadError.value = null
    upcomingError.value = null
    statusError.value = null
    guideError.value = null
  }

  const profileContext = useProfileContextGuard(resetProfileState)

  /** 拒绝对不属于当前 Profile 列表的旧任务执行写操作。 */
  function requireCurrentJob(jobId: string): void {
    if (!jobs.value.some(job => matchId(job, jobId))) {
      throw new Error('Job no longer belongs to the current profile')
    }
  }

  async function fetchJobs() {
    const contextVersion = profileContext.capture()
    loading.value = true
    loadError.value = null
    try {
      const result = await jobsApi.listJobs()
      if (!profileContext.isCurrent(contextVersion)) return
      jobs.value = result
    } catch (err) {
      if (profileContext.isCurrent(contextVersion)) {
        console.error('Failed to fetch jobs:', err)
        loadError.value = errorMessage(err, 'Failed to fetch jobs')
      }
    } finally {
      if (profileContext.isCurrent(contextVersion)) loading.value = false
    }
  }

  async function fetchUpcomingJobs(limit = 5) {
    const contextVersion = profileContext.capture()
    upcomingLoading.value = true
    upcomingError.value = null
    try {
      const result = await jobsApi.listUpcomingJobs(limit)
      if (!profileContext.isCurrent(contextVersion)) return
      upcomingJobs.value = result
    } catch (err) {
      if (profileContext.isCurrent(contextVersion)) {
        console.error('Failed to fetch upcoming jobs:', err)
        upcomingError.value = errorMessage(err, 'Failed to fetch upcoming jobs')
      }
    } finally {
      if (profileContext.isCurrent(contextVersion)) upcomingLoading.value = false
    }
  }

  async function fetchStatus(limit = 5) {
    const contextVersion = profileContext.capture()
    statusLoading.value = true
    statusError.value = null
    try {
      const result = await jobsApi.fetchCronStatus(true, limit)
      if (!profileContext.isCurrent(contextVersion)) return
      status.value = result
    } catch (err) {
      if (profileContext.isCurrent(contextVersion)) {
        console.error('Failed to fetch cron status:', err)
        statusError.value = errorMessage(err, 'Failed to fetch cron status')
      }
    } finally {
      if (profileContext.isCurrent(contextVersion)) statusLoading.value = false
    }
  }

  async function fetchGuideAndPolicy() {
    const contextVersion = profileContext.capture()
    guideLoading.value = true
    guideError.value = null
    try {
      const [guideResult, policyResult] = await Promise.all([
        jobsApi.fetchCronGuide(),
        jobsApi.fetchCronPolicy(),
      ])
      if (!profileContext.isCurrent(contextVersion)) return
      guide.value = guideResult
      policy.value = policyResult
    } catch (err) {
      if (profileContext.isCurrent(contextVersion)) {
        console.error('Failed to fetch cron guide:', err)
        guideError.value = errorMessage(err, 'Failed to fetch cron guide')
      }
    } finally {
      if (profileContext.isCurrent(contextVersion)) guideLoading.value = false
    }
  }

  async function createJob(data: CreateJobRequest): Promise<Job> {
    const contextVersion = profileContext.capture()
    const job = await jobsApi.createJob(data)
    if (!profileContext.isCurrent(contextVersion)) return job
    jobs.value.unshift(job)
    return job
  }

  async function updateJob(jobId: string, data: UpdateJobRequest): Promise<Job> {
    requireCurrentJob(jobId)
    const contextVersion = profileContext.capture()
    const job = await jobsApi.updateJob(jobId, data)
    if (!profileContext.isCurrent(contextVersion)) return job
    const idx = jobs.value.findIndex(j => matchId(j, jobId))
    if (idx !== -1) jobs.value[idx] = job
    return job
  }

  async function deleteJob(jobId: string) {
    requireCurrentJob(jobId)
    const contextVersion = profileContext.capture()
    await jobsApi.deleteJob(jobId)
    if (!profileContext.isCurrent(contextVersion)) return
    jobs.value = jobs.value.filter(j => !matchId(j, jobId))
  }

  async function pauseJob(jobId: string, reason?: string) {
    requireCurrentJob(jobId)
    const contextVersion = profileContext.capture()
    const job = await jobsApi.pauseJob(jobId, reason)
    if (!profileContext.isCurrent(contextVersion)) return
    const idx = jobs.value.findIndex(j => matchId(j, jobId))
    if (idx !== -1) jobs.value[idx] = job
  }

  async function resumeJob(jobId: string) {
    requireCurrentJob(jobId)
    const contextVersion = profileContext.capture()
    const job = await jobsApi.resumeJob(jobId)
    if (!profileContext.isCurrent(contextVersion)) return
    const idx = jobs.value.findIndex(j => matchId(j, jobId))
    if (idx !== -1) jobs.value[idx] = job
  }

  async function runJob(jobId: string) {
    requireCurrentJob(jobId)
    const contextVersion = profileContext.capture()
    const job = await jobsApi.runJob(jobId)
    if (!profileContext.isCurrent(contextVersion)) return
    const idx = jobs.value.findIndex(j => matchId(j, jobId))
    if (idx !== -1) jobs.value[idx] = job
  }

  async function retryJob(jobId: string) {
    requireCurrentJob(jobId)
    const contextVersion = profileContext.capture()
    const job = await jobsApi.retryJob(jobId)
    if (!profileContext.isCurrent(contextVersion)) return
    const idx = jobs.value.findIndex(j => matchId(j, jobId))
    if (idx !== -1) jobs.value[idx] = job
  }

  async function fetchJobRuns(jobId: string, limit = 20) {
    return jobsApi.fetchJobRuns(jobId, limit)
  }

  async function fetchJob(jobId: string) {
    return jobsApi.getJob(jobId)
  }

  async function inspectJob(jobId: string, limit = 20): Promise<JobInspectResult> {
    return jobsApi.inspectJob(jobId, limit)
  }

  return {
    jobs,
    upcomingJobs,
    status,
    guide,
    policy,
    loading,
    upcomingLoading,
    statusLoading,
    guideLoading,
    loadError,
    upcomingError,
    statusError,
    guideError,
    fetchJobs,
    fetchUpcomingJobs,
    fetchStatus,
    fetchGuideAndPolicy,
    fetchJob,
    inspectJob,
    createJob,
    updateJob,
    deleteJob,
    pauseJob,
    resumeJob,
    runJob,
    retryJob,
    fetchJobRuns,
  }
})
