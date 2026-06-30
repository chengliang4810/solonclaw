<script setup lang="ts">
import { Switch } from 'antdv-next'
import SettingRow from './SettingRow.vue'

defineProps<{
  readonly label: string
  readonly hint: string
  readonly value: boolean
  readonly loading: boolean
}>()

const emit = defineEmits<{
  change: [value: boolean]
}>()

function handleChange(value: unknown) {
  emit('change', value === true)
}
</script>

<template>
  <SettingRow :label="label" :hint="hint">
    <Switch
      :value="value"
      :loading="loading"
      @update:value="handleChange"
    />
  </SettingRow>
</template>
