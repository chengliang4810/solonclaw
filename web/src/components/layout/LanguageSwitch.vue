<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Select } from 'antdv-next'
import type { SelectValue } from 'antdv-next'

const { locale } = useI18n()

const options = [
  { label: '中文', value: 'zh' },
  { label: 'English', value: 'en' },
  { label: '日本語', value: 'ja' },
  { label: '한국어', value: 'ko' },
  { label: 'Français', value: 'fr' },
  { label: 'Español', value: 'es' },
  { label: 'Deutsch', value: 'de' },
  { label: 'Português', value: 'pt' },
]

function handleChange(val: SelectValue) {
  if (typeof val !== 'string') return
  locale.value = val
  localStorage.setItem('solonclaw_locale', val)
}
</script>

<template>
  <Select
    :value="locale"
    :options="options"
    size="small"
    class="input-sm"
    @update:value="handleChange"
  />
</template>
