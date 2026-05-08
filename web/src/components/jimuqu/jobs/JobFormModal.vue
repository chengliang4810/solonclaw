<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { NModal, NForm, NFormItem, NInput, NButton, NSelect, NInputNumber, NSwitch, useMessage } from 'naive-ui'
import { useJobsStore } from '@/stores/jimuqu/jobs'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

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
  deliver: 'origin',
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
})

const presetValue = ref<string | null>(null)

const isEdit = computed(() => !!props.jobId)

const schedulePresets = computed(() => [
  { label: t('jobs.presetEveryMinute'), value: '* * * * *' },
  { label: t('jobs.presetEvery5Min'), value: '*/5 * * * *' },
  { label: t('jobs.presetEveryHour'), value: '0 * * * *' },
  { label: t('jobs.presetEveryDay'), value: '0 0 * * *' },
  { label: t('jobs.presetEveryDay9'), value: '0 9 * * *' },
  { label: t('jobs.presetEveryMonday'), value: '0 9 * * 1' },
  { label: t('jobs.presetEveryMonth'), value: '0 9 1 * *' },
])

const originalSchedule = ref<{ kind: string; raw?: string; expr?: string; display: string } | null>(null)

function editableScheduleValue(schedule: any, fallback: string) {
  if (!schedule || typeof schedule === 'string') return schedule || fallback
  return schedule.raw || schedule.expr || schedule.display || fallback
}

function splitCsv(value: string) {
  return value.split(',').map(item => item.trim()).filter(Boolean)
}

function joinCsv(value?: string[] | null) {
  return (value || []).join(', ')
}

onMounted(async () => {
  if (props.jobId) {
    try {
      const { getJob } = await import('@/api/jimuqu/jobs')
      const job = await getJob(props.jobId)
      formData.value = {
        name: job.name,
        schedule: editableScheduleValue(job.schedule, job.schedule_display || ''),
        prompt: job.prompt,
        deliver: job.deliver || 'origin',
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
      }
      if (typeof job.schedule === 'object' && job.schedule) {
        originalSchedule.value = job.schedule
      }
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
  if (!formData.value.schedule.trim()) {
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

  loading.value = true
  try {
    const skills = splitCsv(formData.value.skills_text)
    const contextFrom = splitCsv(formData.value.context_from_text)
    const enabledToolsets = splitCsv(formData.value.enabled_toolsets_text)
    const payload: any = {
      name: formData.value.name,
      schedule: formData.value.schedule,
      prompt: formData.value.prompt,
      deliver: formData.value.deliver,
      repeat: formData.value.repeat_times ?? undefined,
      skills,
      wrap_response: formData.value.wrap_response,
      no_agent: formData.value.no_agent,
      context_from: contextFrom,
      enabled_toolsets: enabledToolsets,
    }
    const nullableFields = [
      ['script', formData.value.script],
      ['workdir', formData.value.workdir],
      ['provider', formData.value.provider],
      ['model', formData.value.model],
      ['base_url', formData.value.base_url],
    ]
    for (const [key, raw] of nullableFields) {
      const value = String(raw).trim()
      if (value) payload[key] = value
      else if (isEdit.value) payload[key] = null
    }

    if (isEdit.value && originalSchedule.value) {
      payload.schedule = {
        kind: originalSchedule.value.kind,
        expr: formData.value.schedule,
        display: formData.value.schedule,
      }
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

      <NFormItem :label="t('jobs.schedule')" required>
        <NInput
          v-model:value="formData.schedule"
          :placeholder="t('jobs.schedulePlaceholder')"
        />
      </NFormItem>

      <NFormItem :label="t('jobs.quickPresets')">
        <NSelect
          v-model:value="presetValue"
          :options="schedulePresets"
          :placeholder="t('jobs.selectPreset')"
          @update:value="v => formData.schedule = v"
        />
      </NFormItem>

      <div class="form-grid">
        <NFormItem :label="t('jobs.skills')">
          <NInput
            v-model:value="formData.skills_text"
            :placeholder="t('jobs.skillsPlaceholder')"
          />
        </NFormItem>

        <NFormItem :label="t('jobs.deliverTarget')">
          <NInput
            v-model:value="formData.deliver"
            :placeholder="t('jobs.deliverPlaceholder')"
          />
        </NFormItem>
      </div>

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
