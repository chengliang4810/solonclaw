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
  workdir: string | null
  no_agent: boolean
  context_from: string[]
  enabled_toolsets: string[]
  wrap_response: boolean
  deliver_chat_id: string | null
  deliver_thread_id: string | null
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
  last_output: string | null
}

export interface JobRun {
  run_id: string
  job_id: string
  source_key: string | null
  trigger: string
  attempt: number
  started_at: string | null
  finished_at: string | null
  finished?: boolean
  duration_ms?: number | null
  status: string | null
  output: string | null
  error: string | null
  delivery_error: string | null
  delivery_result?: JobRunDeliveryResult | null
  summary: string | null
}

export interface JobRunDeliveryResultTarget {
  platform?: string | null
  chat_id?: string | null
  thread_id?: string | null
  status?: string | null
  attachments?: number | null
  error?: string | null
}

export interface JobRunDeliveryResult {
  total?: number
  delivered?: number
  failed?: number
  skipped?: string | null
  error?: string | null
  targets?: JobRunDeliveryResultTarget[]
}

export interface JobInspectResult {
  job: Job
  runs: JobRun[]
  run_count: number
  limit: number
}

export interface CronGuide {
  objective: string
  schedule_types: string[]
  editable_fields: string[]
  actions: Record<string, string>
  aliases: Record<string, string[]>
  skill_binding: Record<string, string[] | boolean>
  delivery: Record<string, string[] | string>
  runtime_modes: Record<string, string[] | string>
  history_and_status: Record<string, string[] | string>
  security: Record<string, string[] | string>
  slash_examples: string[]
  api_routes: string[]
}

export interface CreateJobRequest {
  name: string
  schedule: string
  prompt?: string
  deliver?: string
  deliver_chat_id?: string
  deliver_thread_id?: string
  skills?: string[]
  repeat?: number
  wrap_response?: boolean
  script?: string
  workdir?: string
  no_agent?: boolean
  context_from?: string[]
  enabled_toolsets?: string[]
  model?: string
  provider?: string
  base_url?: string
}

export interface UpdateJobRequest {
  name?: string
  schedule?: string | { kind: string; raw?: string; expr?: string; run_at?: string | number; minutes?: number; display: string }
  prompt?: string
  deliver?: string
  deliver_chat_id?: string | null
  deliver_thread_id?: string | null
  skills?: string[]
  skill?: string
  repeat?: number | null
  enabled?: boolean
  wrap_response?: boolean
  script?: string | null
  workdir?: string | null
  no_agent?: boolean
  context_from?: string[]
  enabled_toolsets?: string[]
  model?: string | null
  provider?: string | null
  base_url?: string | null
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
  workdir?: string | null
  no_agent?: boolean
  context_from?: string[]
  enabled_toolsets?: string[]
  wrap_response?: boolean
  deliver_chat_id?: string | null
  deliver_thread_id?: string | null
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
  last_output?: string | null
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
    workdir: job.workdir || null,
    no_agent: Boolean(job.no_agent),
    context_from: job.context_from || [],
    enabled_toolsets: job.enabled_toolsets || [],
    wrap_response: job.wrap_response !== false,
    deliver_chat_id: job.deliver_chat_id || null,
    deliver_thread_id: job.deliver_thread_id || null,
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
    last_output: job.last_output || null,
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

export async function fetchCronGuide(): Promise<CronGuide> {
  return request<CronGuide>('/api/cron/jobs/guide')
}

export async function getJob(jobId: string): Promise<Job> {
  const job = await request<DashboardJob>(`/api/cron/jobs/${encodeURIComponent(jobId)}`)
  return mapJob(job)
}

export async function inspectJob(jobId: string, limit = 5): Promise<JobInspectResult> {
  const result = await request<{
    job: DashboardJob
    runs: JobRun[]
    run_count: number
    limit: number
  }>(
    `/api/cron/jobs/${encodeURIComponent(jobId)}/inspect?limit=${encodeURIComponent(String(limit))}`,
  )
  return {
    job: mapJob(result.job),
    runs: result.runs || [],
    run_count: result.run_count || 0,
    limit: result.limit || limit,
  }
}

export async function listUpcomingJobs(limit = 5): Promise<Job[]> {
  const result = await request<{ jobs: DashboardJob[]; count: number }>(
    `/api/cron/jobs/next?limit=${encodeURIComponent(String(limit))}`,
  )
  return (result.jobs || []).map(mapJob)
}

export async function createJob(data: CreateJobRequest): Promise<Job> {
  const job = await request<DashboardJob>('/api/cron/jobs', {
    method: 'POST',
    body: JSON.stringify({
      name: data.name,
      prompt: data.prompt || '',
      schedule: data.schedule,
      deliver: data.deliver || 'local',
      deliver_chat_id: data.deliver_chat_id,
      deliver_thread_id: data.deliver_thread_id,
      skills: data.skills || [],
      repeat: data.repeat,
      wrap_response: data.wrap_response,
      script: data.script,
      workdir: data.workdir,
      no_agent: data.no_agent,
      context_from: data.context_from,
      enabled_toolsets: data.enabled_toolsets,
      model: data.model,
      provider: data.provider,
      base_url: data.base_url,
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
      deliver_chat_id: data.deliver_chat_id,
      deliver_thread_id: data.deliver_thread_id,
      skills: data.skills,
      skill: data.skill,
      repeat: data.repeat,
      enabled: data.enabled,
      wrap_response: data.wrap_response,
      script: data.script,
      workdir: data.workdir,
      no_agent: data.no_agent,
      context_from: data.context_from,
      enabled_toolsets: data.enabled_toolsets,
      model: data.model,
      provider: data.provider,
      base_url: data.base_url,
    }),
  })
  return mapJob(job)
}

export async function deleteJob(jobId: string): Promise<{ ok: boolean }> {
  return request<{ ok: boolean }>(`/api/cron/jobs/${jobId}`, {
    method: 'DELETE',
  })
}

export async function pauseJob(jobId: string, reason?: string): Promise<Job> {
  await request<{ ok: boolean }>(`/api/cron/jobs/${jobId}/pause`, {
    method: 'POST',
    body: JSON.stringify({ reason: reason?.trim() || undefined }),
  })
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

export async function fetchJobRuns(jobId: string, limit = 20): Promise<JobRun[]> {
  const result = await request<{ job_id: string; runs: JobRun[]; count: number }>(
    `/api/cron/jobs/${jobId}/runs?limit=${encodeURIComponent(String(limit))}`,
  )
  return result.runs || []
}
