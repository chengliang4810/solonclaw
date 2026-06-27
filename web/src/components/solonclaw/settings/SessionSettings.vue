<script setup lang="ts">
import { Switch } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useSessionBrowserPrefsStore } from '@/stores/solonclaw/session-browser-prefs'
import SettingRow from './SettingRow.vue'

const sessionBrowserPrefsStore = useSessionBrowserPrefsStore()
const { t } = useI18n()
</script>

<template>
  <section class="settings-section">
    <SettingRow :label="t('settings.session.liveMonitorHumanOnly')" :hint="t('settings.session.liveMonitorHumanOnlyHint')">
      <Switch
        :value="sessionBrowserPrefsStore.humanOnly"
        @update:value="value => sessionBrowserPrefsStore.setHumanOnly(value === true)"
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
