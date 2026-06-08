<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { NModal, NForm, NFormItem, NInput, NButton, NSelect, NInputNumber, NSwitch, useMessage } from 'naive-ui'
import { useJobsStore } from '@/stores/solonclaw/jobs'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

type DeliveryMode = 'origin' | 'local' | 'platform' | 'specific' | 'multi'

const props = defineProps<{
  jobId: string | null
}>()

const emit = defineEmits<{
  close: []
  saved: []
}>()

const jobsStore = useJobsStore()
const message = useMessage()

const showModal = ref(true)
const loading = ref(false)

const formData = ref({
  name: '',
  schedule: '',
  prompt: '',
  deliver: 'local',
  deliver_chat_id: '',
  deliver_thread_id: '',
  repeat_times: null as number | null,
  skills_text: '',
  wrap_response: true,
  script: '',
  workdir: '',
  no_agent: false,
  context_from_text: '',
  enabled_toolsets_text: '',
  provider: '',
  model: '',
  base_url: '',
  state: 'scheduled',
  enabled: true,
  paused_reason: '',
})

const presetValue = ref<string | null>(null)
const scheduleKind = ref<'cron' | 'interval' | 'once'>('cron')
const intervalAmount = ref<number | null>(30)
const intervalUnit = ref<'m' | 'h' | 'd'>('m')
const deliveryMode = ref<DeliveryMode>('local')
const deliveryPlatform = ref('feishu')
const deliveryMultiText = ref('')
const skillEditMode = ref<'replace' | 'merge' | 'clear'>('replace')
const addSkillsText = ref('')
const removeSkillsText = ref('')

const isEdit = computed(() => !!props.jobId)

const scheduleKindOptions = computed(() => [
  { label: t('jobs.scheduleKindCron'), value: 'cron' },
  { label: t('jobs.scheduleKindInterval'), value: 'interval' },
  { label: t('jobs.scheduleKindOnce'), value: 'once' },
])

const intervalUnitOptions = computed(() => [
  { label: t('jobs.intervalMinutes'), value: 'm' },
  { label: t('jobs.intervalHours'), value: 'h' },
  { label: t('jobs.intervalDays'), value: 'd' },
])

const stateOptions = computed(() => [
  { label: t('jobs.stateScheduled'), value: 'scheduled' },
  { label: t('jobs.statePaused'), value: 'paused' },
  { label: t('jobs.stateCompleted'), value: 'completed' },
])

const deliveryModeOptions = computed(() => [
  { label: t('jobs.deliveryModeOrigin'), value: 'origin' },
  { label: t('jobs.deliveryModeLocal'), value: 'local' },
  { label: t('jobs.deliveryModePlatform'), value: 'platform' },
  { label: t('jobs.deliveryModeSpecific'), value: 'specific' },
  { label: t('jobs.deliveryModeMulti'), value: 'multi' },
])

const deliveryPlatformOptions = computed(() => [
  { label: t('jobs.platformFeishu'), value: 'feishu' },
  { label: t('jobs.platformDingtalk'), value: 'dingtalk' },
  { label: t('jobs.platformWecom'), value: 'wecom' },
  { label: t('jobs.platformWeixin'), value: 'weixin' },
  { label: t('jobs.platformQqbot'), value: 'qqbot' },
  { label: t('jobs.platformYuanbao'), value: 'yuanbao' },
])

const skillEditModeOptions = computed(() => [
  { label: t('jobs.skillEditReplace'), value: 'replace' },
  { label: t('jobs.skillEditMerge'), value: 'merge' },
  { label: t('jobs.skillEditClear'), value: 'clear' },
])

const schedulePresets = computed(() => [
  { label: t('jobs.presetEveryMinute'), value: '* * * * *' },
  { label: t('jobs.presetEvery5Min'), value: '*/5 * * * *' },
  { label: t('jobs.presetEveryHour'), value: '0 * * * *' },
  { label: t('jobs.presetEveryDay'), value: '0 0 * * *' },
  { label: t('jobs.presetEveryDay9'), value: '0 9 * * *' },
  { label: t('jobs.presetEveryMonday'), value: '0 9 * * 1' },
  { label: t('jobs.presetEveryMonth'), value: '0 9 1 * *' },
])

function editableScheduleValue(schedule: any, fallback: string) {
  if (!schedule || typeof schedule === 'string') return schedule || fallback
  if (schedule.run_at && !schedule.raw && !schedule.expr) {
    return typeof schedule.run_at === 'number' ? new Date(schedule.run_at).toISOString() : String(schedule.run_at)
  }
  return schedule.raw || schedule.expr || schedule.display || fallback
}

function parseIntervalSchedule(value: string) {
  const match = value.trim().match(/^every\s+(\d+)\s*(m|min|minute|minutes|h|hr|hrs|hour|hours|d|day|days)$/i)
  if (!match) return null
  const unit = match[2].toLowerCase()
  return {
    amount: Number(match[1]),
    unit: unit.startsWith('h') ? 'h' as const : unit.startsWith('d') ? 'd' as const : 'm' as const,
  }
}

function looksLikeOneShot(value: string) {
  const trimmed = value.trim()
  return /^\d+\s*(m|min|minute|minutes|h|hr|hrs|hour|hours|d|day|days)$/i.test(trimmed)
    || /^\d{4}-\d{2}-\d{2}T/.test(trimmed)
}

function applyIntervalMinutes(minutes?: number | null) {
  if (!minutes || minutes < 1) return
  if (minutes % 1440 === 0) {
    intervalAmount.value = minutes / 1440
    intervalUnit.value = 'd'
  } else if (minutes % 60 === 0) {
    intervalAmount.value = minutes / 60
    intervalUnit.value = 'h'
  } else {
    intervalAmount.value = minutes
    intervalUnit.value = 'm'
  }
}

function inferScheduleControls(schedule: any, fallback: string) {
  const value = editableScheduleValue(schedule, fallback)
  const kind = typeof schedule === 'object' && schedule?.kind ? schedule.kind : ''
  if (kind === 'interval' || parseIntervalSchedule(value)) {
    scheduleKind.value = 'interval'
    const parsed = parseIntervalSchedule(value)
    if (parsed) {
      intervalAmount.value = parsed.amount
      intervalUnit.value = parsed.unit
    } else if (typeof schedule?.minutes === 'number') {
      applyIntervalMinutes(schedule.minutes)
    }
    formData.value.schedule = buildIntervalSchedule()
    return
  }
  if (kind === 'once' || looksLikeOneShot(value)) {
    scheduleKind.value = 'once'
    formData.value.schedule = value
    return
  }
  scheduleKind.value = 'cron'
  formData.value.schedule = value
}

function buildIntervalSchedule() {
  const amount = Math.max(1, Math.floor(intervalAmount.value || 1))
  return `every ${amount}${intervalUnit.value}`
}

function buildScheduleValue() {
  if (scheduleKind.value === 'interval') {
    return buildIntervalSchedule()
  }
  return formData.value.schedule.trim()
}

function splitCsv(value: string) {
  return value.split(',').map(item => item.trim()).filter(Boolean)
}

function joinCsv(value?: string[] | null) {
  return (value || []).join(', ')
}

function supportedDeliveryPlatform(value: string) {
  return deliveryPlatformOptions.value.some(option => option.value === value.trim().toLowerCase())
}

function splitDeliveryTarget(value: string) {
  const parts = value.trim().split(':')
  return {
    platform: (parts[0] || '').trim().toLowerCase(),
    chatId: (parts[1] || '').trim(),
    threadId: parts.slice(2).join(':').trim(),
  }
}

function inferDeliveryControls(
  deliver?: string | null,
  chatId?: string | null,
  threadId?: string | null,
  originPlatform?: string | null,
) {
  const value = (deliver || 'origin').trim() || 'origin'
  formData.value.deliver_chat_id = chatId || ''
  formData.value.deliver_thread_id = threadId || ''

  if (value.indexOf(',') >= 0) {
    deliveryMode.value = 'multi'
    deliveryMultiText.value = value
    return
  }

  const target = splitDeliveryTarget(value)
  if ((target.platform === 'origin' || target.platform === 'local') && !chatId && !threadId && !target.chatId) {
    deliveryMode.value = target.platform as DeliveryMode
    deliveryMultiText.value = ''
    return
  }
  if (target.platform === 'origin' && (chatId || threadId)) {
    const platform = (originPlatform || '').trim().toLowerCase()
    if (supportedDeliveryPlatform(platform)) {
      deliveryPlatform.value = platform
      deliveryMode.value = 'specific'
      deliveryMultiText.value = value
      return
    }
  }

  if (supportedDeliveryPlatform(target.platform)) {
    deliveryPlatform.value = target.platform
    if (chatId || threadId || target.chatId || target.threadId) {
      deliveryMode.value = 'specific'
      formData.value.deliver_chat_id = chatId || target.chatId || ''
      formData.value.deliver_thread_id = threadId || target.threadId || ''
    } else {
      deliveryMode.value = 'platform'
    }
    deliveryMultiText.value = value
    return
  }

  deliveryMode.value = 'multi'
  deliveryMultiText.value = value
}

function buildDeliveryPayload() {
  if (deliveryMode.value === 'multi') {
    return {
      deliver: deliveryMultiText.value.trim() || 'local',
      deliver_chat_id: '',
      deliver_thread_id: '',
    }
  }
  if (deliveryMode.value === 'platform') {
    return {
      deliver: deliveryPlatform.value,
      deliver_chat_id: '',
      deliver_thread_id: '',
    }
  }
  if (deliveryMode.value === 'specific') {
    return {
      deliver: deliveryPlatform.value,
      deliver_chat_id: formData.value.deliver_chat_id.trim(),
      deliver_thread_id: formData.value.deliver_thread_id.trim(),
    }
  }
  return {
    deliver: deliveryMode.value,
    deliver_chat_id: '',
    deliver_thread_id: '',
  }
}

onMounted(async () => {
  if (props.jobId) {
    try {
      const { getJob } = await import('@/api/solonclaw/jobs')
      const job = await getJob(props.jobId)
      formData.value = {
        name: job.name,
        schedule: '',
        prompt: job.prompt,
        deliver: job.deliver || 'origin',
        deliver_chat_id: job.deliver_chat_id || '',
        deliver_thread_id: job.deliver_thread_id || '',
        repeat_times: typeof job.repeat === 'number' ? job.repeat : (typeof job.repeat === 'object' ? job.repeat.times : null),
        skills_text: joinCsv(job.skills),
        wrap_response: job.wrap_response,
        script: job.script || '',
        workdir: job.workdir || '',
        no_agent: job.no_agent,
        context_from_text: joinCsv(job.context_from),
        enabled_toolsets_text: joinCsv(job.enabled_toolsets),
        provider: job.provider || '',
        model: job.model || '',
        base_url: job.base_url || '',
        state: job.state === 'completed' || job.state === 'paused' ? job.state : 'scheduled',
        enabled: job.enabled,
        paused_reason: job.paused_reason || '',
      }
      inferDeliveryControls(job.deliver, job.deliver_chat_id, job.deliver_thread_id, job.origin?.platform)
      inferScheduleControls(job.schedule, job.schedule_display || '')
      skillEditMode.value = 'replace'
      addSkillsText.value = ''
      removeSkillsText.value = ''
    } catch (e: any) {
      message.error(t('jobs.loadFailed') + ': ' + e.message)
    }
  }
})

async function handleSave() {
  if (!formData.value.name.trim()) {
    message.warning(t('jobs.nameRequired'))
    return
  }
  const scheduleValue = buildScheduleValue()
  if (!scheduleValue) {
    message.warning(t('jobs.scheduleRequired'))
    return
  }
  if (formData.value.no_agent && !formData.value.script.trim()) {
    message.warning(t('jobs.scriptRequiredForNoAgent'))
    return
  }
  if (!formData.value.no_agent && !formData.value.prompt.trim() && splitCsv(formData.value.skills_text).length === 0) {
    message.warning(t('jobs.promptOrSkillRequired'))
    return
  }
  if (deliveryMode.value === 'specific' && !formData.value.deliver_chat_id.trim()) {
    message.warning(t('jobs.deliverChatIdRequired'))
    return
  }

  loading.value = true
  try {
    const skills = splitCsv(formData.value.skills_text)
    const contextFrom = splitCsv(formData.value.context_from_text)
    const enabledToolsets = splitCsv(formData.value.enabled_toolsets_text)
    const repeatValue = formData.value.repeat_times
    const deliveryPayload = buildDeliveryPayload()
    const payload: any = {
      name: formData.value.name,
      schedule: scheduleValue,
      prompt: formData.value.prompt,
      deliver: deliveryPayload.deliver,
      deliver_chat_id: deliveryPayload.deliver_chat_id || undefined,
      deliver_thread_id: deliveryPayload.deliver_thread_id || undefined,
      repeat: repeatValue ?? (isEdit.value ? null : undefined),
      skills,
      wrap_response: formData.value.wrap_response,
      no_agent: formData.value.no_agent,
      context_from: contextFrom,
      enabled_toolsets: enabledToolsets,
      enabled: formData.value.state === 'scheduled',
      state: formData.value.state === 'scheduled' ? 'active' : formData.value.state,
      paused_reason: formData.value.paused_reason.trim() || undefined,
    }
    if (isEdit.value && skillEditMode.value === 'merge') {
      delete payload.skills
      payload.add_skills = splitCsv(addSkillsText.value)
      payload.remove_skills = splitCsv(removeSkillsText.value)
    } else if (isEdit.value && skillEditMode.value === 'clear') {
      delete payload.skills
      payload.clear_skills = true
    }
    const nullableFields = [
      ['script', formData.value.script],
      ['workdir', formData.value.workdir],
      ['provider', formData.value.provider],
      ['model', formData.value.model],
      ['base_url', formData.value.base_url],
      ['deliver_chat_id', deliveryPayload.deliver_chat_id],
      ['deliver_thread_id', deliveryPayload.deliver_thread_id],
      ['paused_reason', formData.value.paused_reason],
    ]
    for (const [key, raw] of nullableFields) {
      const value = String(raw).trim()
      if (value) payload[key] = value
      else if (isEdit.value) payload[key] = null
    }

    if (isEdit.value) {
      await jobsStore.updateJob(props.jobId!, payload)
      message.success(t('jobs.jobUpdated'))
    } else {
      await jobsStore.createJob(payload)
      message.success(t('jobs.jobCreated'))
    }
    emit('saved')
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

function handleClose() {
  showModal.value = false
  setTimeout(() => emit('close'), 200)
}

function handlePresetChange(value: string) {
  scheduleKind.value = 'cron'
  formData.value.schedule = value
}
</script>

<template>
  <NModal
    v-model:show="showModal"
    preset="card"
    :title="isEdit ? t('jobs.editJob') : t('jobs.createJob')"
    :style="{ width: 'min(760px, calc(100vw - 32px))' }"
    :mask-closable="!loading"
    @after-leave="emit('close')"
  >
    <NForm label-placement="top">
      <NFormItem :label="t('jobs.name')" required>
        <NInput
          v-model:value="formData.name"
          :placeholder="t('jobs.namePlaceholder')"
          maxlength="200"
          show-count
        />
      </NFormItem>

      <div class="schedule-panel">
        <div class="form-grid">
          <NFormItem :label="t('jobs.scheduleKind')" required>
            <NSelect
              v-model:value="scheduleKind"
              :options="scheduleKindOptions"
            />
          </NFormItem>

          <NFormItem
            v-if="scheduleKind === 'cron'"
            :label="t('jobs.quickPresets')"
          >
            <NSelect
              v-model:value="presetValue"
              :options="schedulePresets"
              :placeholder="t('jobs.selectPreset')"
              @update:value="handlePresetChange"
            />
          </NFormItem>
        </div>

        <NFormItem
          v-if="scheduleKind === 'cron'"
          :label="t('jobs.scheduleCron')"
          required
        >
          <NInput
            v-model:value="formData.schedule"
            :placeholder="t('jobs.schedulePlaceholder')"
          />
        </NFormItem>

        <div v-else-if="scheduleKind === 'interval'" class="form-grid">
          <NFormItem :label="t('jobs.intervalAmount')" required>
            <NInputNumber
              v-model:value="intervalAmount"
              :min="1"
              :precision="0"
              style="width: 100%"
            />
          </NFormItem>

          <NFormItem :label="t('jobs.intervalUnit')" required>
            <NSelect
              v-model:value="intervalUnit"
              :options="intervalUnitOptions"
            />
          </NFormItem>
        </div>

        <NFormItem
          v-else
          :label="t('jobs.scheduleOnce')"
          required
        >
          <NInput
            v-model:value="formData.schedule"
            :placeholder="t('jobs.scheduleOncePlaceholder')"
          />
        </NFormItem>
      </div>

      <div class="form-grid">
        <NFormItem v-if="isEdit" :label="t('jobs.skillEditMode')">
          <NSelect
            v-model:value="skillEditMode"
            :options="skillEditModeOptions"
          />
        </NFormItem>

        <NFormItem
          v-if="!isEdit || skillEditMode === 'replace'"
          :label="t('jobs.skills')"
        >
          <NInput
            v-model:value="formData.skills_text"
            :placeholder="t('jobs.skillsPlaceholder')"
          />
        </NFormItem>

        <template v-if="isEdit && skillEditMode === 'merge'">
          <NFormItem :label="t('jobs.addSkills')">
            <NInput
              v-model:value="addSkillsText"
              :placeholder="t('jobs.addSkillsPlaceholder')"
            />
          </NFormItem>

          <NFormItem :label="t('jobs.removeSkills')">
            <NInput
              v-model:value="removeSkillsText"
              :placeholder="t('jobs.removeSkillsPlaceholder')"
            />
          </NFormItem>
        </template>

        <NFormItem
          v-if="isEdit && skillEditMode === 'clear'"
          :label="t('jobs.skills')"
        >
          <NInput
            :value="t('jobs.clearSkillsNotice')"
            readonly
          />
        </NFormItem>
      </div>

      <div class="form-grid">
        <NFormItem :label="t('jobs.deliveryMode')">
          <NSelect
            v-model:value="deliveryMode"
            :options="deliveryModeOptions"
          />
        </NFormItem>
      </div>

      <div
        v-if="deliveryMode === 'platform' || deliveryMode === 'specific'"
        class="form-grid"
      >
        <NFormItem :label="t('jobs.deliveryPlatform')">
          <NSelect
            v-model:value="deliveryPlatform"
            :options="deliveryPlatformOptions"
          />
        </NFormItem>

        <NFormItem
          v-if="deliveryMode === 'specific'"
          :label="t('jobs.deliverChatId')"
          required
        >
          <NInput
            v-model:value="formData.deliver_chat_id"
            :placeholder="t('jobs.deliverChatIdPlaceholder')"
          />
        </NFormItem>
      </div>

      <div v-if="deliveryMode === 'specific'" class="form-grid">
        <NFormItem :label="t('jobs.deliverThreadId')">
          <NInput
            v-model:value="formData.deliver_thread_id"
            :placeholder="t('jobs.deliverThreadIdPlaceholder')"
          />
        </NFormItem>

        <NFormItem :label="t('jobs.deliverPreview')">
          <NInput
            :value="`${deliveryPlatform}:${formData.deliver_chat_id || t('jobs.deliverPreviewEmptyChat')}${formData.deliver_thread_id ? ':' + formData.deliver_thread_id : ''}`"
            readonly
          />
        </NFormItem>
      </div>

      <NFormItem
        v-if="deliveryMode === 'multi'"
        :label="t('jobs.deliverTarget')"
      >
        <NInput
          v-model:value="deliveryMultiText"
          :placeholder="t('jobs.deliverPlaceholder')"
        />
      </NFormItem>

      <NFormItem :label="t('jobs.prompt')" :required="!formData.no_agent">
        <NInput
          v-model:value="formData.prompt"
          type="textarea"
          :placeholder="t('jobs.promptPlaceholder')"
          :rows="4"
          maxlength="5000"
          show-count
        />
      </NFormItem>

      <div class="form-grid">
        <NFormItem :label="t('jobs.repeatCount')">
          <NInputNumber
            v-model:value="formData.repeat_times"
            :min="1"
            :placeholder="t('jobs.repeatPlaceholder')"
            clearable
            style="width: 100%"
          />
        </NFormItem>

        <NFormItem :label="t('jobs.wrapResponse')">
          <NSwitch v-model:value="formData.wrap_response" />
        </NFormItem>
      </div>

      <div v-if="isEdit" class="form-grid">
        <NFormItem :label="t('jobs.state')">
          <NSelect
            v-model:value="formData.state"
            :options="stateOptions"
          />
        </NFormItem>

        <NFormItem :label="t('jobs.pausedReason')">
          <NInput
            v-model:value="formData.paused_reason"
            :disabled="formData.state !== 'paused'"
            :placeholder="t('jobs.pausedReasonPlaceholder')"
            maxlength="300"
            show-count
          />
        </NFormItem>
      </div>

      <div class="form-grid">
        <NFormItem :label="t('jobs.script')">
          <NInput
            v-model:value="formData.script"
            :placeholder="t('jobs.scriptPlaceholder')"
          />
        </NFormItem>

        <NFormItem :label="t('jobs.noAgent')">
          <NSwitch v-model:value="formData.no_agent" />
        </NFormItem>
      </div>

      <NFormItem :label="t('jobs.workdir')">
        <NInput
          v-model:value="formData.workdir"
          :placeholder="t('jobs.workdirPlaceholder')"
        />
      </NFormItem>

      <div class="form-grid">
        <NFormItem :label="t('jobs.contextFrom')">
          <NInput
            v-model:value="formData.context_from_text"
            :placeholder="t('jobs.contextFromPlaceholder')"
          />
        </NFormItem>

        <NFormItem :label="t('jobs.enabledToolsets')">
          <NInput
            v-model:value="formData.enabled_toolsets_text"
            :placeholder="t('jobs.enabledToolsetsPlaceholder')"
          />
        </NFormItem>
      </div>

      <div class="form-grid three">
        <NFormItem :label="t('jobs.provider')">
          <NInput v-model:value="formData.provider" :placeholder="t('jobs.providerPlaceholder')" />
        </NFormItem>
        <NFormItem :label="t('jobs.model')">
          <NInput v-model:value="formData.model" :placeholder="t('jobs.modelPlaceholder')" />
        </NFormItem>
        <NFormItem :label="t('jobs.baseUrl')">
          <NInput v-model:value="formData.base_url" :placeholder="t('jobs.baseUrlPlaceholder')" />
        </NFormItem>
      </div>
    </NForm>

    <template #footer>
      <div class="modal-footer">
        <NButton @click="handleClose">{{ t('common.cancel') }}</NButton>
        <NButton type="primary" :loading="loading" @click="handleSave">
          {{ isEdit ? t('common.update') : t('common.create') }}
        </NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped lang="scss">
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.form-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;

  &.three {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .form-grid,
  .form-grid.three {
    grid-template-columns: 1fr;
  }
}
</style>
