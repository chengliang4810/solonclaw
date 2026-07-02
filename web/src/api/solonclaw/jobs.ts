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
  actions?: JobActions
  paused_at: string | null
  paused_reason: string | null
  pending_trigger: string | null
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
  skill_binding: Record<string, unknown>
  delivery: Record<string, unknown>
  runtime_modes: Record<string, unknown>
  runtime_isolation?: Record<string, unknown>
  history_and_status: Record<string, unknown>
  security: Record<string, unknown>
  slash_examples: string[]
  api_routes: string[]
}

export interface JobActions {
  can_inspect?: boolean
  can_edit?: boolean
  can_remove?: boolean
  can_history?: boolean
  can_pause?: boolean
  can_resume?: boolean
  can_run?: boolean
  can_retry?: boolean
  supports_enable_alias?: boolean
  supports_disable_alias?: boolean
  supports_start_alias?: boolean
  supports_stop_alias?: boolean
  supports_rerun_alias?: boolean
}

export interface CronPolicy {
  actions: string[]
  action_syntax: Record<string, string>
  sourceScopedList: boolean
  freshSessionRuns: boolean
  selfContainedPromptRequired: boolean
  recursiveCronCreationDiscouraged: boolean
  trigger_type_fields?: string[]
  custom_manual_trigger_supported?: boolean
  custom_retry_trigger_supported?: boolean
  queued_trigger_type_persisted?: boolean
  update_fields: string[]
  clear_fields: string[]
  status_fields: string[]
  history_fields: string[]
  schedule: Record<string, unknown>
  delivery: Record<string, unknown>
  skill_binding: Record<string, unknown>
  execution: Record<string, unknown>
  runtime_isolation: Record<string, unknown>
}

export interface CronFailure {
  id?: string
  job_id?: string
  name?: string
  last_status?: string | null
  last_error?: string | null
  last_delivery_error?: string | null
  last_run_at?: string | null
}

export interface CronStatus {
  total: number
  active: number
  paused: number
  completed: number
  due: number
  include_disabled: boolean
  limit: number
  next: Job[]
  recent_failures: CronFailure[]
}

export interface Toolset {
  name: string
  label?: string
  description?: string
  enabled?: boolean
  configured?: boolean
  tools?: string[]
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
  add_skill?: string
  add_skills?: string[]
  remove_skill?: string
  remove_skills?: string[]
  clear_skills?: boolean
  skills_delta?: {
    add?: string[]
    remove?: string[]
  }
  repeat?: number | null
  enabled?: boolean
  state?: string
  status?: string
  paused_reason?: string | null
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
  actions?: JobActions
  deliver?: string
  origin?: Job['origin']
  paused_at?: string | null
  paused_reason?: string | null
  pending_trigger?: string | null
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
    actions: job.actions,
    paused_at: job.paused_at || null,
    paused_reason: job.paused_reason || null,
    pending_trigger: job.pending_trigger || null,
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

function encodeJobPath(jobId: string): string {
  return `/api/cron/jobs/${encodeURIComponent(jobId)}`
}

export async function listJobs(): Promise<Job[]> {
  const jobs = await request<DashboardJob[]>('/api/cron/jobs')
  return jobs.map(mapJob)
}

export async function fetchCronGuide(): Promise<CronGuide> {
  return request<CronGuide>('/api/cron/jobs/guide')
}

export async function fetchCronPolicy(): Promise<CronPolicy> {
  return request<CronPolicy>('/api/cron/jobs/policy')
}

export async function fetchCronStatus(includeDisabled = true, limit = 5): Promise<CronStatus> {
  const params = new URLSearchParams({
    include_disabled: String(includeDisabled),
    limit: String(limit),
  })
  const status = await request<Omit<CronStatus, 'next'> & { next?: DashboardJob[] }>(
    `/api/cron/jobs/status?${params.toString()}`,
  )
  return {
    total: status.total || 0,
    active: status.active || 0,
    paused: status.paused || 0,
    completed: status.completed || 0,
    due: status.due || 0,
    include_disabled: status.include_disabled !== false,
    limit: status.limit || limit,
    next: (status.next || []).map(mapJob),
    recent_failures: status.recent_failures || [],
  }
}

export async function fetchToolsets(): Promise<Toolset[]> {
  return request<Toolset[]>('/api/tools/toolsets')
}

export async function getJob(jobId: string): Promise<Job> {
  const job = await request<DashboardJob>(encodeJobPath(jobId))
  return mapJob(job)
}

export async function inspectJob(jobId: string, limit = 5): Promise<JobInspectResult> {
  const result = await request<{
    job: DashboardJob
    runs: JobRun[]
    run_count: number
    limit: number
  }>(
    `${encodeJobPath(jobId)}/inspect?limit=${encodeURIComponent(String(limit))}`,
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
  const job = await request<DashboardJob>(encodeJobPath(jobId), {
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
      add_skill: data.add_skill,
      add_skills: data.add_skills,
      remove_skill: data.remove_skill,
      remove_skills: data.remove_skills,
      clear_skills: data.clear_skills,
      skills_delta: data.skills_delta,
      repeat: data.repeat,
      enabled: data.enabled,
      state: data.state,
      status: data.status,
      paused_reason: data.paused_reason,
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
  return request<{ ok: boolean }>(encodeJobPath(jobId), {
    method: 'DELETE',
  })
}

export async function pauseJob(jobId: string, reason?: string): Promise<Job> {
  await request<{ ok: boolean }>(`${encodeJobPath(jobId)}/pause`, {
    method: 'POST',
    body: JSON.stringify({ reason: reason?.trim() || undefined }),
  })
  return getJob(jobId)
}

export async function resumeJob(jobId: string): Promise<Job> {
  await request<{ ok: boolean }>(`${encodeJobPath(jobId)}/resume`, { method: 'POST' })
  return getJob(jobId)
}

export async function runJob(jobId: string): Promise<Job> {
  await request<{ ok: boolean }>(`${encodeJobPath(jobId)}/trigger`, {
    method: 'POST',
    body: JSON.stringify({ trigger_type: 'dashboard' }),
  })
  return getJob(jobId)
}

export async function retryJob(jobId: string): Promise<Job> {
  await request<{ ok: boolean }>(`${encodeJobPath(jobId)}/retry`, {
    method: 'POST',
    body: JSON.stringify({ trigger_type: 'dashboard_retry' }),
  })
  return getJob(jobId)
}

export async function fetchJobRuns(jobId: string, limit = 20): Promise<JobRun[]> {
  const result = await request<{ job_id: string; runs: JobRun[]; count: number }>(
    `${encodeJobPath(jobId)}/runs?limit=${encodeURIComponent(String(limit))}`,
  )
  return result.runs || []
}
