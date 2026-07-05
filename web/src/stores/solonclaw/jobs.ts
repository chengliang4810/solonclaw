import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as jobsApi from '@/api/solonclaw/jobs'
import type { Job, CreateJobRequest, UpdateJobRequest, CronGuide, CronPolicy, CronStatus, JobInspectResult } from '@/api/solonclaw/jobs'

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

  async function fetchJobs() {
    loading.value = true
    loadError.value = null
    try {
      jobs.value = await jobsApi.listJobs()
    } catch (err) {
      console.error('Failed to fetch jobs:', err)
      loadError.value = errorMessage(err, 'Failed to fetch jobs')
    } finally {
      loading.value = false
    }
  }

  async function fetchUpcomingJobs(limit = 5) {
    upcomingLoading.value = true
    upcomingError.value = null
    try {
      upcomingJobs.value = await jobsApi.listUpcomingJobs(limit)
    } catch (err) {
      console.error('Failed to fetch upcoming jobs:', err)
      upcomingError.value = errorMessage(err, 'Failed to fetch upcoming jobs')
    } finally {
      upcomingLoading.value = false
    }
  }

  async function fetchStatus(limit = 5) {
    statusLoading.value = true
    statusError.value = null
    try {
      status.value = await jobsApi.fetchCronStatus(true, limit)
    } catch (err) {
      console.error('Failed to fetch cron status:', err)
      statusError.value = errorMessage(err, 'Failed to fetch cron status')
    } finally {
      statusLoading.value = false
    }
  }

  async function fetchGuideAndPolicy() {
    guideLoading.value = true
    guideError.value = null
    try {
      const [guideResult, policyResult] = await Promise.all([
        jobsApi.fetchCronGuide(),
        jobsApi.fetchCronPolicy(),
      ])
      guide.value = guideResult
      policy.value = policyResult
    } catch (err) {
      console.error('Failed to fetch cron guide:', err)
      guideError.value = errorMessage(err, 'Failed to fetch cron guide')
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
