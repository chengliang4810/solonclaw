import { request } from '../client'

export type ProfileTaskStatus =
  | 'PENDING'
  | 'READY'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'TIMED_OUT'
  | 'BLOCKED'
  | 'CANCELLED'
  | 'INTERRUPTED'

export interface ProfileTask {
  taskId: string
  sourceProfile: string
  targetProfile: string
  title: string
  prompt: string
  status: ProfileTaskStatus
  dependencyIds: string[]
  attemptCount: number
  maxAttempts: number
  timeoutMinutes: number
  result?: string
  error?: string
  createdAt: number
  updatedAt: number
}

export interface CreateProfileTaskRequest {
  source_profile: string
  target_profile: string
  description: string
  depends_on: string[]
  timeout_minutes: number
}

export async function fetchProfileTasks(assignee: string): Promise<ProfileTask[]> {
  const data = await request<{ value: ProfileTask[] }>(`/api/profile-tasks?assignee=${encodeURIComponent(assignee)}`)
  return data.value
}

export async function createProfileTask(body: CreateProfileTaskRequest): Promise<ProfileTask> {
  const data = await request<{ value: ProfileTask }>('/api/profile-tasks', {
    method: 'POST',
    body: JSON.stringify(body),
  })
  return data.value
}
