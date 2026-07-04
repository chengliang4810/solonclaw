<script setup lang="ts">
import { Switch, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/solonclaw/settings'
import SettingRow from './SettingRow.vue'

const settingsStore = useSettingsStore()
const { t } = useI18n()

async function save(values: Record<string, any>) {
  try {
    await settingsStore.saveSection('gateway', values)
    message.success(t('settings.saved'))
  } catch (err: any) {
    message.error(t('settings.saveFailed'))
  }
}
</script>

<template>
  <section class="settings-section">
    <SettingRow
      :label="t('settings.gateway.processingReactionsEnabled')"
      :hint="t('settings.gateway.processingReactionsEnabledHint')"
    >
      <Switch
        :value="settingsStore.gateway.processingReactionsEnabled"
        @update:value="v => save({ processingReactionsEnabled: v })"
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
