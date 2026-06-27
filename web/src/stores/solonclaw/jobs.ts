import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as jobsApi from '@/api/solonclaw/jobs'
import type { Job, CreateJobRequest, UpdateJobRequest, CronGuide, CronPolicy, CronStatus } from '@/api/solonclaw/jobs'

function matchId(job: Job, id: string): boolean {
  return job.job_id === id || job.id === id
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
  const errors = ref<Record<string, string>>({})
  const error = computed(() => Object.values(errors.value).filter(Boolean).join('\n'))

  function setError(key: string, err: unknown) {
    errors.value = { ...errors.value, [key]: err instanceof Error ? err.message : String(err || '') }
  }

  function clearError(key: string) {
    if (!errors.value[key]) return
    const next = { ...errors.value }
    delete next[key]
    errors.value = next
  }

  async function fetchJobs() {
    loading.value = true
    clearError('jobs')
    try {
      jobs.value = await jobsApi.listJobs()
    } catch (err) {
      jobs.value = []
      setError('jobs', err)
      console.error('Failed to fetch jobs:', err)
    } finally {
      loading.value = false
    }
  }

  async function fetchUpcomingJobs(limit = 5) {
    upcomingLoading.value = true
    clearError('upcoming')
    try {
      upcomingJobs.value = await jobsApi.listUpcomingJobs(limit)
    } catch (err) {
      upcomingJobs.value = []
      setError('upcoming', err)
      console.error('Failed to fetch upcoming jobs:', err)
    } finally {
      upcomingLoading.value = false
    }
  }

  async function fetchStatus(limit = 5) {
    statusLoading.value = true
    clearError('status')
    try {
      status.value = await jobsApi.fetchCronStatus(true, limit)
    } catch (err) {
      status.value = null
      setError('status', err)
      console.error('Failed to fetch cron status:', err)
    } finally {
      statusLoading.value = false
    }
  }

  async function fetchGuideAndPolicy() {
    guideLoading.value = true
    clearError('guide')
    try {
      const [guideResult, policyResult] = await Promise.all([
        jobsApi.fetchCronGuide(),
        jobsApi.fetchCronPolicy(),
      ])
      guide.value = guideResult
      policy.value = policyResult
    } catch (err) {
      guide.value = null
      policy.value = null
      setError('guide', err)
      console.error('Failed to fetch cron guide:', err)
    } finally {
      guideLoading.value = false
    }
  }

  async function createJob(data: CreateJobRequest): Promise<Job> {
    const job = await jobsApi.createJob(data)
    jobs.value.unshift(job)
    return job
  }

  async function updateJob(jobId: string, data: UpdateJobRequest): Promise<Job> {
    const job = await jobsApi.updateJob(jobId, data)
    const idx = jobs.value.findIndex(j => matchId(j, jobId))
    if (idx !== -1) jobs.value[idx] = job
    return job
  }

  async function deleteJob(jobId: string) {
    await jobsApi.deleteJob(jobId)
    jobs.value = jobs.value.filter(j => !matchId(j, jobId))
  }

  async function pauseJob(jobId: string, reason?: string) {
    const job = await jobsApi.pauseJob(jobId, reason)
    const idx = jobs.value.findIndex(j => matchId(j, jobId))
    if (idx !== -1) jobs.value[idx] = job
  }

  async function resumeJob(jobId: string) {
    const job = await jobsApi.resumeJob(jobId)
    const idx = jobs.value.findIndex(j => matchId(j, jobId))
    if (idx !== -1) jobs.value[idx] = job
  }

  async function runJob(jobId: string) {
    const job = await jobsApi.runJob(jobId)
    const idx = jobs.value.findIndex(j => matchId(j, jobId))
    if (idx !== -1) jobs.value[idx] = job
  }

  async function retryJob(jobId: string) {
    const job = await jobsApi.retryJob(jobId)
    const idx = jobs.value.findIndex(j => matchId(j, jobId))
    if (idx !== -1) jobs.value[idx] = job
  }

  async function fetchJobRuns(jobId: string, limit = 20) {
    return jobsApi.fetchJobRuns(jobId, limit)
  }

  async function fetchJob(jobId: string) {
    return jobsApi.getJob(jobId)
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
    error,
    fetchJobs,
    fetchUpcomingJobs,
    fetchStatus,
    fetchGuideAndPolicy,
    fetchJob,
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
