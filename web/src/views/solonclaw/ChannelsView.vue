<script setup lang="ts">
import { onMounted } from 'vue'
import { NSpin } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/solonclaw/settings'
import PlatformSettings from '@/components/solonclaw/settings/PlatformSettings.vue'
import PlatformToolsetsPanel from '@/components/solonclaw/settings/PlatformToolsetsPanel.vue'

const settingsStore = useSettingsStore()
const { t } = useI18n()

onMounted(() => {
  settingsStore.fetchSettings()
})
</script>

<template>
  <div class="channels-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('sidebar.channels') }}</h2>
        <p class="header-subtitle">{{ t('channels.description') }}</p>
      </div>
    </header>

    <div class="channels-content">
      <NSpin :show="settingsStore.loading || settingsStore.saving" size="large" :description="t('common.loading')">
        <PlatformToolsetsPanel />
        <PlatformSettings v-if="!settingsStore.loading" />
      </NSpin>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.channels-view {
  height: calc(100 * var(--vh));
  display: flex;
  flex-direction: column;
}

.channels-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  position: relative;
}
</style>
