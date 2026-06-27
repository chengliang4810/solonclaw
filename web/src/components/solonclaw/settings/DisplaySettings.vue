<script setup lang="ts">
import { Switch, Select, message } from 'antdv-next'
import type { SelectValue } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/solonclaw/settings'
import { useTheme, type ThemeMode } from '@/composables/useTheme'
import SettingRow from './SettingRow.vue'

const settingsStore = useSettingsStore()
const { t } = useI18n()
const { mode, setMode } = useTheme()

const themeOptions = [
  { label: t('settings.display.themeLight'), value: 'light' },
  { label: t('settings.display.themeDark'), value: 'dark' },
  { label: t('settings.display.themeSystem'), value: 'system' },
]

async function save(values: Record<string, any>) {
  try {
    await settingsStore.saveSection('display', values)
    message.success(t('settings.saved'))
  } catch (err: any) {
    message.error(t('settings.saveFailed'))
  }
}

function handleThemeChange(val: SelectValue) {
  if (val !== 'light' && val !== 'dark' && val !== 'system') return
  setMode(val as ThemeMode)
}
</script>

<template>
  <section class="settings-section">
    <SettingRow :label="t('settings.display.theme')" :hint="t('settings.display.themeHint')">
      <Select :value="mode" :options="themeOptions" size="small" class="input-sm" @update:value="handleThemeChange" />
    </SettingRow>
    <SettingRow :label="t('settings.display.streaming')" :hint="t('settings.display.streamingHint')">
      <Switch :value="settingsStore.display.streaming" @update:value="v => save({ streaming: v })" />
    </SettingRow>
    <SettingRow :label="t('settings.display.showReasoning')" :hint="t('settings.display.showReasoningHint')">
      <Switch :value="settingsStore.display.show_reasoning" @update:value="v => save({ show_reasoning: v })" />
    </SettingRow>
  </section>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.settings-section {
  margin-top: 16px;
}
</style>
