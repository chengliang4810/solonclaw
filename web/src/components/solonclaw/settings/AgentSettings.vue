<script setup lang="ts">
import { InputNumber, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/solonclaw/settings'
import SettingRow from './SettingRow.vue'

const settingsStore = useSettingsStore()
const { t } = useI18n()

async function save(values: Record<string, any>) {
  try {
    await settingsStore.saveSection('agent', values)
    message.success(t('settings.saved'))
  } catch (err: any) {
    message.error(t('settings.saveFailed'))
  }
}
</script>

<template>
  <section class="settings-section">
    <SettingRow :label="t('settings.agent.maxTurns')" :hint="t('settings.agent.maxTurnsHint')">
      <InputNumber
        :value="settingsStore.agent.max_turns"
        :min="1" :max="200" :step="5"
        size="small" class="input-sm"
        @update:value="v => v != null && save({ max_turns: v })"
      />
    </SettingRow>
  </section>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.settings-section {
  margin-top: 16px;
}
</style>
