<script setup lang="ts">
import { Input } from 'antdv-next'
import SettingRow from './SettingRow.vue'

defineProps<{
  readonly label: string
  readonly hint: string
  readonly value: string
  readonly loading: boolean
  readonly placeholder: string
}>()

const emit = defineEmits<{
  change: [value: string]
}>()

function handleChange(value: unknown) {
  if (typeof value === 'string') {
    emit('change', value)
    return
  }
  if (value instanceof Event) {
    const target = value.target
    if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) {
      emit('change', target.value)
      return
    }
  }
  emit('change', '')
}
</script>

<template>
  <SettingRow :label="label" :hint="hint">
    <Input
      :default-value="value"
      :loading="loading"
      clearable
      size="small"
      class="input-lg"
      :placeholder="placeholder"
      @change="handleChange"
    />
  </SettingRow>
</template>
