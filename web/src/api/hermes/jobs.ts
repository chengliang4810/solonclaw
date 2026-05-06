import { request } from '../client'

export interface Job {
  job_id: string
  id: string
  name: string
  prompt: string
  prompt_preview?: string
  skills: string[]
  skill: string | null
  model: string | null
  provider: string | null
  base_url: string | null
  script: string | null
  schedule: string | { kind: string; raw?: string; expr?: string; run_at?: string | number; minutes?: number; display: string }
  schedule_display: string
  repeat: string | { times: number | null; completed: number }
  enabled: boolean
  state: string
  paused_at: string | null
  paused_reason: string | null
  created_at: string
  next_run_at: string | null
  last_run_at: string | null
  last_status: string | null
  last_error: string | null
  deliver: string
  origin: {
    platform: string
    chat_id: string
    chat_name: string
    thread_id: string | null
  } | null
  last_delivery_error: string | null
}

export interface CreateJobRequest {
  name: string
  schedule: string
  prompt?: string
  deliver?: string
  skills?: string[]
  repeat?: number
}

export interface UpdateJobRequest {
  name?: string
  schedule?: string | { kind: string; raw?: string; expr?: string; run_at?: string | number; minutes?: number; display: string }
  prompt?: string
  deliver?: string
  skills?: string[]
  skill?: string
  repeat?: number
  enabled?: boolean
}

interface DashboardJob {
  id: string
  job_id?: string
  name?: string
  prompt: string
  prompt_preview?: string
  skills?: string[]
  skill?: string | null
  model?: string | null
  provider?: string | null
  base_url?: string | null
  script?: string | null
  repeat?: string | { times: number | null; completed: number }
  schedule: { kind: string; raw?: string; expr?: string; run_at?: string | number; minutes?: number; display: string }
  schedule_display: string
  enabled: boolean
  state: string
  deliver?: string
  origin?: Job['origin']
  paused_at?: string | null
  paused_reason?: string | null
  last_run_at?: string | null
  next_run_at?: string | null
  last_status?: string | null
  last_error?: string | null
  last_delivery_error?: string | null
}

function mapJob(job: DashboardJob): Job {
  const state = job.enabled ? job.state || 'scheduled' : 'paused'
  return {
    job_id: job.id,
    id: job.id,
    name: job.name || '',
    prompt: job.prompt,
    prompt_preview: job.prompt_preview || job.prompt.slice(0, 120),
    skills: job.skills || [],
    skill: job.skill || null,
    model: job.model || null,
    provider: job.provider || null,
    base_url: job.base_url || null,
    script: job.script || null,
    schedule: job.schedule,
    schedule_display: job.schedule_display || job.schedule.display,
    repeat: job.repeat || 'forever',
    enabled: job.enabled,
    state,
    paused_at: job.paused_at || null,
    paused_reason: job.paused_reason || null,
    created_at: (job as any).created_at || job.last_run_at || job.next_run_at || new Date().toISOString(),
    next_run_at: job.next_run_at || null,
    last_run_at: job.last_run_at || null,
    last_status: job.last_status || state,
    last_error: job.last_error || null,
    deliver: job.deliver || 'local',
    origin: job.origin || null,
    last_delivery_error: job.last_delivery_error || null,
  }
}

function unwrapSchedule(schedule: string | { kind: string; raw?: string; expr?: string; run_at?: string | number; minutes?: number; display: string } | undefined): string | undefined {
  if (!schedule) return undefined
  if (typeof schedule === 'string') return schedule
  return schedule.raw || schedule.expr || schedule.display
}

export async function listJobs(): Promise<Job[]> {
  const jobs = await request<DashboardJob[]>('/api/cron/jobs')
  return jobs.map(mapJob)
}

export async function getJob(jobId: string): Promise<Job> {
  const jobs = await listJobs()
  const found = jobs.find((job) => job.id === jobId || job.job_id === jobId)
  if (!found) throw new Error('Job not found')
  return found
}

export async function createJob(data: CreateJobRequest): Promise<Job> {
  const job = await request<DashboardJob>('/api/cron/jobs', {
    method: 'POST',
    body: JSON.stringify({
      name: data.name,
      prompt: data.prompt || '',
      schedule: data.schedule,
      deliver: data.deliver || 'local',
      skills: data.skills || [],
      repeat: data.repeat,
    }),
  })
  return mapJob(job)
}

export async function updateJob(jobId: string, data: UpdateJobRequest): Promise<Job> {
  const job = await request<DashboardJob>(`/api/cron/jobs/${jobId}`, {
    method: 'PUT',
    body: JSON.stringify({
      name: data.name,
      prompt: data.prompt,
      schedule: unwrapSchedule(data.schedule),
      deliver: data.deliver,
      skills: data.skills,
      skill: data.skill,
      repeat: data.repeat,
      enabled: data.enabled,
    }),
  })
  return mapJob(job)
}

export async function deleteJob(jobId: string): Promise<{ ok: boolean }> {
  return request<{ ok: boolean }>(`/api/cron/jobs/${jobId}`, {
    method: 'DELETE',
  })
}

export async function pauseJob(jobId: string): Promise<Job> {
  await request<{ ok: boolean }>(`/api/cron/jobs/${jobId}/pause`, { method: 'POST' })
  return getJob(jobId)
}

export async function resumeJob(jobId: string): Promise<Job> {
  await request<{ ok: boolean }>(`/api/cron/jobs/${jobId}/resume`, { method: 'POST' })
  return getJob(jobId)
}

export async function runJob(jobId: string): Promise<Job> {
  await request<{ ok: boolean }>(`/api/cron/jobs/${jobId}/trigger`, { method: 'POST' })
  return getJob(jobId)
}
