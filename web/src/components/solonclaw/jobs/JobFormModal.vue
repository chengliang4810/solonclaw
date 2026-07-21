<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { Modal, Form, FormItem, Input, Button, Select, InputNumber, Switch, TextArea, message } from 'antdv-next'
import type { SelectValue } from 'antdv-next'
import { fetchToolsets, type Toolset } from '@/api/solonclaw/jobs'
import { useJobsStore } from '@/stores/solonclaw/jobs'
import { useModelsStore } from '@/stores/solonclaw/models'
import { useI18n } from 'vue-i18n'
import {
  DOMESTIC_PLATFORM_KEYS,
  DOMESTIC_PLATFORM_LABEL_KEYS,
  isDomesticPlatformKey,
  type DomesticPlatformKey,
} from '@/shared/domesticPlatforms'
import {
  JOB_DELIVERY_MODE_OPTIONS,
  JOB_INTERVAL_UNIT_OPTIONS,
  JOB_SCHEDULE_KIND_OPTIONS,
  JOB_SCHEDULE_PRESET_OPTIONS,
  JOB_SKILL_EDIT_MODE_OPTIONS,
  JOB_STATE_OPTIONS,
  translateJobFormOptions,
  type JobDeliveryMode,
  type JobIntervalUnit,
  type JobScheduleKind,
  type JobSkillEditMode,
} from '@/shared/jobFormOptions'
import { hasText, joinTextList, splitTrimmedText, trimText } from '@/shared/text'

const { t } = useI18n()

const props = defineProps<{
  jobId: string | null
}>()

const emit = defineEmits<{
  close: []
  saved: []
}>()

const jobsStore = useJobsStore()
const modelsStore = useModelsStore()

const showModal = ref(true)
const loading = ref(false)
const toolsetsLoading = ref(false)
const showAdvanced = ref(false)
const toolsets = ref<Toolset[]>([])

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
  enabled_toolsets: [] as string[],
  provider: '',
  model: '',
  base_url: '',
  state: 'scheduled',
  enabled: true,
  paused_reason: '',
})

const presetValue = ref<string | null>(null)
const scheduleKind = ref<JobScheduleKind>('cron')
const intervalAmount = ref<number | null>(30)
const intervalUnit = ref<JobIntervalUnit>('m')
const deliveryMode = ref<JobDeliveryMode>('local')
const deliveryPlatform = ref<DomesticPlatformKey>('feishu')
const deliveryMultiText = ref('')
const skillEditMode = ref<JobSkillEditMode>('replace')
const addSkillsText = ref('')
const removeSkillsText = ref('')

const isEdit = computed(() => !!props.jobId)

const scheduleKindOptions = computed(() => translateJobFormOptions(t, JOB_SCHEDULE_KIND_OPTIONS))
const intervalUnitOptions = computed(() => translateJobFormOptions(t, JOB_INTERVAL_UNIT_OPTIONS))
const stateOptions = computed(() => translateJobFormOptions(t, JOB_STATE_OPTIONS))
const deliveryModeOptions = computed(() => translateJobFormOptions(t, JOB_DELIVERY_MODE_OPTIONS))

const deliveryPlatformOptions = computed(() => DOMESTIC_PLATFORM_KEYS.map(value => ({
  label: t(DOMESTIC_PLATFORM_LABEL_KEYS[value]),
  value,
})))

const skillEditModeOptions = computed(() => translateJobFormOptions(t, JOB_SKILL_EDIT_MODE_OPTIONS))
const schedulePresets = computed(() => translateJobFormOptions(t, JOB_SCHEDULE_PRESET_OPTIONS))
const toolsetOptions = computed(() => {
  const options = toolsets.value.map(toolset => ({
    label: toolset.label ? `${toolset.label} (${toolset.name})` : toolset.name,
    value: toolset.name,
    disabled: toolset.enabled === false || toolset.configured === false,
  }))
  const known = new Set(options.map(option => option.value))
  for (const value of formData.value.enabled_toolsets) {
    if (value && !known.has(value)) {
      options.push({ label: value, value, disabled: false })
    }
  }
  return options
})

const providerOptions = computed(() => {
  const options = modelsStore.providers.map(provider => ({
    label: provider.label,
    value: provider.provider,
    disabled: false,
  }))
  const currentProvider = formData.value.provider
  if (currentProvider && !modelsStore.providers.some(provider => provider.provider === currentProvider)) {
    options.push({
      label: t('models.unregisteredProvider', { provider: currentProvider }),
      value: currentProvider,
      disabled: true,
    })
  }
  return options
})

const modelOptions = computed(() => {
  const provider = modelsStore.providers.find(item => item.provider === formData.value.provider)
  const models = Array.from(new Set(provider?.models || []))
  const options = models.map(model => ({ label: model, value: model, disabled: false }))
  const currentModel = formData.value.model
  if (currentModel && !models.includes(currentModel)) {
    options.push({
      label: t('models.unregisteredModel', { model: currentModel }),
      value: currentModel,
      disabled: true,
    })
  }
  return options
})

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
  return trimText(formData.value.schedule)
}

function splitCsv(value: string) {
  return splitTrimmedText(value, ',')
}

function splitDeliveryTarget(value: string) {
  const parts = trimText(value).split(':')
  return {
    platform: trimText(parts[0]).toLowerCase(),
    chatId: trimText(parts[1]),
    threadId: trimText(parts.slice(2).join(':')),
  }
}

function inferDeliveryControls(
  deliver?: string | null,
  chatId?: string | null,
  threadId?: string | null,
  originPlatform?: string | null,
) {
  const value = trimText(deliver) || 'origin'
  formData.value.deliver_chat_id = chatId || ''
  formData.value.deliver_thread_id = threadId || ''

  if (value.indexOf(',') >= 0) {
    deliveryMode.value = 'multi'
    deliveryMultiText.value = value
    return
  }

  const target = splitDeliveryTarget(value)
  if ((target.platform === 'origin' || target.platform === 'local') && !chatId && !threadId && !target.chatId) {
    deliveryMode.value = target.platform
    deliveryMultiText.value = ''
    return
  }
  if (target.platform === 'origin' && (chatId || threadId)) {
    const platform = trimText(originPlatform).toLowerCase()
    if (isDomesticPlatformKey(platform)) {
      deliveryPlatform.value = platform
      deliveryMode.value = 'specific'
      deliveryMultiText.value = value
      return
    }
  }

  if (isDomesticPlatformKey(target.platform)) {
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
      deliver: trimText(deliveryMultiText.value) || 'local',
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
      deliver_chat_id: trimText(formData.value.deliver_chat_id),
      deliver_thread_id: trimText(formData.value.deliver_thread_id),
    }
  }
  return {
    deliver: deliveryMode.value,
    deliver_chat_id: '',
    deliver_thread_id: '',
  }
}

onMounted(async () => {
  void loadToolsets()
  if (modelsStore.providers.length === 0) {
    await modelsStore.fetchProviders()
  }
  if (props.jobId) {
    try {
      const job = await jobsStore.fetchJob(props.jobId)
      const storedProvider = String(job.provider || '').trim()
      const storedModel = String(job.model || '').trim()
      const hasCompleteModelBinding = Boolean(storedProvider && storedModel)
      formData.value = {
        name: job.name,
        schedule: '',
        prompt: job.prompt,
        deliver: job.deliver || 'origin',
        deliver_chat_id: job.deliver_chat_id || '',
        deliver_thread_id: job.deliver_thread_id || '',
        repeat_times: typeof job.repeat === 'number' ? job.repeat : (typeof job.repeat === 'object' ? job.repeat.times : null),
        skills_text: joinTextList(job.skills),
        wrap_response: job.wrap_response,
        script: job.script || '',
        workdir: job.workdir || '',
        no_agent: job.no_agent,
        context_from_text: joinTextList(job.context_from),
        enabled_toolsets: [...job.enabled_toolsets],
        provider: hasCompleteModelBinding ? storedProvider : '',
        model: hasCompleteModelBinding ? storedModel : '',
        base_url: '',
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

function handleProviderChange(value?: string) {
  formData.value.provider = value || ''
  const provider = modelsStore.providers.find(item => item.provider === formData.value.provider)
  formData.value.model = provider?.defaultModel || provider?.models[0] || ''
  formData.value.base_url = ''
}

async function loadToolsets() {
  toolsetsLoading.value = true
  try {
    toolsets.value = await fetchToolsets()
  } catch {
    message.error(t('common.fetchFailed'))
  } finally {
    toolsetsLoading.value = false
  }
}

async function handleSave() {
  if (!hasText(formData.value.name)) {
    message.warning(t('jobs.nameRequired'))
    return
  }
  const scheduleValue = buildScheduleValue()
  if (!scheduleValue) {
    message.warning(t('jobs.scheduleRequired'))
    return
  }
  if (formData.value.no_agent && !hasText(formData.value.script)) {
    message.warning(t('jobs.scriptRequiredForNoAgent'))
    return
  }
  if (!formData.value.no_agent && !hasText(formData.value.prompt) && splitCsv(formData.value.skills_text).length === 0) {
    message.warning(t('jobs.promptOrSkillRequired'))
    return
  }
  if (deliveryMode.value === 'specific' && !hasText(formData.value.deliver_chat_id)) {
    message.warning(t('jobs.deliverChatIdRequired'))
    return
  }
  if (hasText(formData.value.provider) !== hasText(formData.value.model)) {
    message.warning(t('jobs.providerModelPairRequired'))
    return
  }

  loading.value = true
  try {
    const skills = splitCsv(formData.value.skills_text)
    const contextFrom = splitCsv(formData.value.context_from_text)
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
      enabled_toolsets: formData.value.enabled_toolsets,
      enabled: formData.value.state === 'scheduled',
      state: formData.value.state === 'scheduled' ? 'active' : formData.value.state,
      paused_reason: trimText(formData.value.paused_reason) || undefined,
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
      const value = trimText(typeof raw === 'string' ? raw : '')
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

function handlePresetChange(value: SelectValue) {
  if (typeof value !== 'string') return
  scheduleKind.value = 'cron'
  formData.value.schedule = value
}
</script>

<template>
  <Modal
    v-model:open="showModal"

    :title="isEdit ? t('jobs.editJob') : t('jobs.createJob')"
    :style="{ width: 'min(760px, calc(100vw - 32px))' }"
    :mask-closable="!loading"
    @cancel="handleClose"
  >
    <Form layout="vertical">
      <section class="wizard-section">
        <div class="section-head">
          <span class="section-number">1</span>
          <div>
            <h3>{{ t('jobs.stepTaskTitle') }}</h3>
            <p>{{ t('jobs.stepTaskDesc') }}</p>
          </div>
        </div>
        <FormItem :label="t('jobs.name')" required>
          <Input
            v-model:value="formData.name"
            :placeholder="t('jobs.namePlaceholder')"
            :maxlength="200"
            show-count
          />
        </FormItem>
        <FormItem :label="t('jobs.prompt')" :required="!formData.no_agent">
          <TextArea
            v-model:value="formData.prompt"
            :placeholder="t('jobs.promptPlaceholder')"
            :rows="5"
            :maxlength="5000"
            show-count
          />
        </FormItem>
      </section>

      <section class="wizard-section">
        <div class="section-head">
          <span class="section-number">2</span>
          <div>
            <h3>{{ t('jobs.stepTimeTitle') }}</h3>
            <p>{{ t('jobs.stepTimeDesc') }}</p>
          </div>
        </div>
        <div class="form-grid">
          <FormItem :label="t('jobs.scheduleKind')" required>
            <Select
              v-model:value="scheduleKind"
              :options="scheduleKindOptions"
            />
          </FormItem>

          <FormItem
            v-if="scheduleKind === 'cron'"
            :label="t('jobs.quickPresets')"
          >
            <Select
              v-model:value="presetValue"
              :options="schedulePresets"
              :placeholder="t('jobs.selectPreset')"
              @update:value="handlePresetChange"
            />
          </FormItem>
        </div>

        <FormItem
          v-if="scheduleKind === 'cron'"
          :label="t('jobs.scheduleCron')"
          required
        >
          <Input
            v-model:value="formData.schedule"
            :placeholder="t('jobs.schedulePlaceholder')"
          />
        </FormItem>

        <div v-else-if="scheduleKind === 'interval'" class="form-grid">
          <FormItem :label="t('jobs.intervalAmount')" required>
            <InputNumber
              v-model:value="intervalAmount"
              :min="1"
              :precision="0"
              style="width: 100%"
            />
          </FormItem>

          <FormItem :label="t('jobs.intervalUnit')" required>
            <Select
              v-model:value="intervalUnit"
              :options="intervalUnitOptions"
            />
          </FormItem>
        </div>

        <FormItem
          v-else
          :label="t('jobs.scheduleOnce')"
          required
        >
          <Input
            v-model:value="formData.schedule"
            :placeholder="t('jobs.scheduleOncePlaceholder')"
          />
        </FormItem>
      </section>

      <section class="wizard-section">
        <div class="section-head">
          <span class="section-number">3</span>
          <div>
            <h3>{{ t('jobs.stepDeliveryTitle') }}</h3>
            <p>{{ t('jobs.stepDeliveryDesc') }}</p>
          </div>
        </div>
        <div class="form-grid">
          <FormItem :label="t('jobs.deliveryMode')">
            <Select
              v-model:value="deliveryMode"
              :options="deliveryModeOptions"
            />
          </FormItem>
        </div>

        <div
          v-if="deliveryMode === 'platform' || deliveryMode === 'specific'"
          class="form-grid"
        >
          <FormItem :label="t('jobs.deliveryPlatform')">
            <Select
              v-model:value="deliveryPlatform"
              :options="deliveryPlatformOptions"
            />
          </FormItem>

          <FormItem
            v-if="deliveryMode === 'specific'"
            :label="t('jobs.deliverChatId')"
            required
          >
            <Input
              v-model:value="formData.deliver_chat_id"
              :placeholder="t('jobs.deliverChatIdPlaceholder')"
            />
          </FormItem>
        </div>

        <div v-if="deliveryMode === 'specific'" class="form-grid">
          <FormItem :label="t('jobs.deliverThreadId')">
            <Input
              v-model:value="formData.deliver_thread_id"
              :placeholder="t('jobs.deliverThreadIdPlaceholder')"
            />
          </FormItem>

          <FormItem :label="t('jobs.deliverPreview')">
            <Input
              :value="`${deliveryPlatform}:${formData.deliver_chat_id || t('jobs.deliverPreviewEmptyChat')}${formData.deliver_thread_id ? ':' + formData.deliver_thread_id : ''}`"
              readonly
            />
          </FormItem>
        </div>

        <FormItem
          v-if="deliveryMode === 'multi'"
          :label="t('jobs.deliverTarget')"
        >
          <Input
            v-model:value="deliveryMultiText"
            :placeholder="t('jobs.deliverPlaceholder')"
          />
        </FormItem>
      </section>

      <div class="advanced-toggle">
        <Button type="text" size="small" @click="showAdvanced = !showAdvanced">
          {{ showAdvanced ? t('jobs.advancedHide') : t('jobs.advancedShow') }}
        </Button>
      </div>

      <section v-if="showAdvanced" class="wizard-section advanced-section">
        <div class="section-head">
          <span class="section-number">+</span>
          <div>
            <h3>{{ t('jobs.advancedTitle') }}</h3>
            <p>{{ t('jobs.advancedDesc') }}</p>
          </div>
        </div>

        <div class="form-grid">
          <FormItem v-if="isEdit" :label="t('jobs.skillEditMode')">
            <Select
              v-model:value="skillEditMode"
              :options="skillEditModeOptions"
            />
          </FormItem>

          <FormItem
            v-if="!isEdit || skillEditMode === 'replace'"
            :label="t('jobs.skills')"
          >
            <Input
              v-model:value="formData.skills_text"
              :placeholder="t('jobs.skillsPlaceholder')"
            />
          </FormItem>

          <template v-if="isEdit && skillEditMode === 'merge'">
            <FormItem :label="t('jobs.addSkills')">
              <Input
                v-model:value="addSkillsText"
                :placeholder="t('jobs.addSkillsPlaceholder')"
              />
            </FormItem>

            <FormItem :label="t('jobs.removeSkills')">
              <Input
                v-model:value="removeSkillsText"
                :placeholder="t('jobs.removeSkillsPlaceholder')"
              />
            </FormItem>
          </template>

          <FormItem
            v-if="isEdit && skillEditMode === 'clear'"
            :label="t('jobs.skills')"
          >
            <Input
              :value="t('jobs.clearSkillsNotice')"
              readonly
            />
          </FormItem>
        </div>

        <div class="form-grid">
          <FormItem :label="t('jobs.repeatCount')">
            <InputNumber
              v-model:value="formData.repeat_times"
              :min="1"
              :placeholder="t('jobs.repeatPlaceholder')"
              style="width: 100%"
            />
          </FormItem>

          <FormItem :label="t('jobs.wrapResponse')">
            <Switch v-model:value="formData.wrap_response" />
          </FormItem>
        </div>

        <div v-if="isEdit" class="form-grid">
          <FormItem :label="t('jobs.state')">
            <Select
              v-model:value="formData.state"
              :options="stateOptions"
            />
          </FormItem>

          <FormItem :label="t('jobs.pausedReason')">
            <Input
              v-model:value="formData.paused_reason"
              :disabled="formData.state !== 'paused'"
              :placeholder="t('jobs.pausedReasonPlaceholder')"
              :maxlength="300"
              show-count
            />
          </FormItem>
        </div>

        <div class="form-grid">
          <FormItem :label="t('jobs.script')">
            <Input
              v-model:value="formData.script"
              :placeholder="t('jobs.scriptPlaceholder')"
            />
          </FormItem>

          <FormItem :label="t('jobs.noAgent')">
            <Switch v-model:value="formData.no_agent" />
          </FormItem>
        </div>

        <FormItem :label="t('jobs.workdir')">
          <Input
            v-model:value="formData.workdir"
            :placeholder="t('jobs.workdirPlaceholder')"
          />
        </FormItem>

        <div class="form-grid">
          <FormItem :label="t('jobs.contextFrom')">
            <Input
              v-model:value="formData.context_from_text"
              :placeholder="t('jobs.contextFromPlaceholder')"
            />
          </FormItem>

          <FormItem :label="t('jobs.enabledToolsets')">
            <Select
              v-model:value="formData.enabled_toolsets"
              mode="multiple"
              :virtual="false"
              :loading="toolsetsLoading"
              :options="toolsetOptions"
              :placeholder="t('jobs.enabledToolsetsPlaceholder')"
            />
          </FormItem>
        </div>

        <div class="form-grid">
          <FormItem :label="t('jobs.provider')">
            <Select
              v-model:value="formData.provider"
              :options="providerOptions"
              :placeholder="t('jobs.useCronDefaultModel')"
              allow-clear
              @change="handleProviderChange"
            />
          </FormItem>
          <FormItem :label="t('jobs.model')">
            <Select
              v-model:value="formData.model"
              :options="modelOptions"
              :placeholder="t('jobs.useCronDefaultModel')"
              :disabled="!formData.provider"
            />
          </FormItem>
        </div>
      </section>
    </Form>

    <template #footer>
      <div class="modal-footer">
        <Button @click="handleClose">{{ t('common.cancel') }}</Button>
        <Button type="primary" :loading="loading" @click="handleSave">
          {{ isEdit ? t('common.update') : t('common.create') }}
        </Button>
      </div>
    </template>
  </Modal>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.wizard-section {
  border: 1px solid $border-light;
  border-radius: 8px;
  background: $bg-card;
  padding: 14px;

  + .wizard-section {
    margin-top: 12px;
  }
}

.section-head {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}

.section-number {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: rgba(var(--accent-primary-rgb), 0.12);
  color: $accent-primary;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
}

.section-head h3 {
  margin: 0;
  color: $text-primary;
  font-size: 14px;
  font-weight: 700;
  line-height: 1.4;
}

.section-head p {
  margin: 3px 0 0;
  color: $text-muted;
  font-size: 12px;
  line-height: 1.5;
}

.advanced-toggle {
  display: flex;
  justify-content: center;
  margin: 10px 0;
}

.advanced-section {
  background: rgba(var(--accent-primary-rgb), 0.03);
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
