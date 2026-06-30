export type JobScheduleKind = 'cron' | 'interval' | 'once'
export type JobIntervalUnit = 'm' | 'h' | 'd'
export type JobStateOption = 'scheduled' | 'paused' | 'completed'
export type JobDeliveryMode = 'origin' | 'local' | 'platform' | 'specific' | 'multi'
export type JobSkillEditMode = 'replace' | 'merge' | 'clear'

export interface JobFormOption<Value extends string> {
  readonly labelKey: string
  readonly value: Value
}

export interface TranslatedJobFormOption<Value extends string> {
  readonly label: string
  readonly value: Value
}

export type JobFormTranslator = (key: string) => string

export const JOB_SCHEDULE_KIND_OPTIONS: readonly JobFormOption<JobScheduleKind>[] = [
  { labelKey: 'jobs.scheduleKindCron', value: 'cron' },
  { labelKey: 'jobs.scheduleKindInterval', value: 'interval' },
  { labelKey: 'jobs.scheduleKindOnce', value: 'once' },
] as const

export const JOB_INTERVAL_UNIT_OPTIONS: readonly JobFormOption<JobIntervalUnit>[] = [
  { labelKey: 'jobs.intervalMinutes', value: 'm' },
  { labelKey: 'jobs.intervalHours', value: 'h' },
  { labelKey: 'jobs.intervalDays', value: 'd' },
] as const

export const JOB_STATE_OPTIONS: readonly JobFormOption<JobStateOption>[] = [
  { labelKey: 'jobs.stateScheduled', value: 'scheduled' },
  { labelKey: 'jobs.statePaused', value: 'paused' },
  { labelKey: 'jobs.stateCompleted', value: 'completed' },
] as const

export const JOB_DELIVERY_MODE_OPTIONS: readonly JobFormOption<JobDeliveryMode>[] = [
  { labelKey: 'jobs.deliveryModeOrigin', value: 'origin' },
  { labelKey: 'jobs.deliveryModeLocal', value: 'local' },
  { labelKey: 'jobs.deliveryModePlatform', value: 'platform' },
  { labelKey: 'jobs.deliveryModeSpecific', value: 'specific' },
  { labelKey: 'jobs.deliveryModeMulti', value: 'multi' },
] as const

export const JOB_SKILL_EDIT_MODE_OPTIONS: readonly JobFormOption<JobSkillEditMode>[] = [
  { labelKey: 'jobs.skillEditReplace', value: 'replace' },
  { labelKey: 'jobs.skillEditMerge', value: 'merge' },
  { labelKey: 'jobs.skillEditClear', value: 'clear' },
] as const

export const JOB_SCHEDULE_PRESET_OPTIONS: readonly JobFormOption<string>[] = [
  { labelKey: 'jobs.presetEveryMinute', value: '* * * * *' },
  { labelKey: 'jobs.presetEvery5Min', value: '*/5 * * * *' },
  { labelKey: 'jobs.presetEveryHour', value: '0 * * * *' },
  { labelKey: 'jobs.presetEveryDay', value: '0 0 * * *' },
  { labelKey: 'jobs.presetEveryDay9', value: '0 9 * * *' },
  { labelKey: 'jobs.presetEveryMonday', value: '0 9 * * 1' },
  { labelKey: 'jobs.presetEveryMonth', value: '0 9 1 * *' },
] as const

export function translateJobFormOptions<Value extends string>(
  t: JobFormTranslator,
  options: readonly JobFormOption<Value>[],
): TranslatedJobFormOption<Value>[] {
  return options.map(option => ({
    label: t(option.labelKey),
    value: option.value,
  }))
}
