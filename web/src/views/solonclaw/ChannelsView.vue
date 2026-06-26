<script setup lang="ts">
import { onMounted } from 'vue'
import { Spin } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/solonclaw/settings'
import PlatformSettings from '@/components/solonclaw/settings/PlatformSettings.vue'

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
      <Spin :spinning="settingsStore.loading || settingsStore.saving" size="large" :tip="t('common.loading')">
        <PlatformSettings v-if="!settingsStore.loading" />
      </Spin>
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
