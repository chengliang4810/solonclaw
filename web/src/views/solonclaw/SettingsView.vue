<script setup lang="ts">
import { onMounted } from "vue";
import {
  NTabs,
  NTabPane,
  NSpin,
} from "naive-ui";
import { useI18n } from "vue-i18n";
import { useSettingsStore } from "@/stores/solonclaw/settings";
import DisplaySettings from "@/components/solonclaw/settings/DisplaySettings.vue";
import AgentSettings from "@/components/solonclaw/settings/AgentSettings.vue";
import MemorySettings from "@/components/solonclaw/settings/MemorySettings.vue";
import ModelSettings from "@/components/solonclaw/settings/ModelSettings.vue";
import SessionSettings from "@/components/solonclaw/settings/SessionSettings.vue";
import PrivacySettings from "@/components/solonclaw/settings/PrivacySettings.vue";
import AccountSettings from "@/components/solonclaw/settings/AccountSettings.vue";

const settingsStore = useSettingsStore();
const { t } = useI18n();

onMounted(() => {
  settingsStore.fetchSettings();
});
</script>

<template>
  <div class="settings-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t("settings.title") }}</h2>
        <p class="header-subtitle">{{ t("settings.description") }}</p>
      </div>
    </header>

    <div class="settings-content">
      <NSpin
        :show="settingsStore.loading || settingsStore.saving"
        size="large"
        :description="t('common.loading')"
      >
        <NTabs type="line" animated>
          <NTabPane name="account" :tab="t('settings.tabs.account')">
            <AccountSettings />
          </NTabPane>
          <NTabPane name="display" :tab="t('settings.tabs.display')">
            <DisplaySettings />
          </NTabPane>
          <NTabPane name="agent" :tab="t('settings.tabs.agent')">
            <AgentSettings />
          </NTabPane>
          <NTabPane name="models" :tab="t('settings.tabs.models')">
            <ModelSettings />
          </NTabPane>
          <NTabPane name="memory" :tab="t('settings.tabs.memory')">
            <MemorySettings />
          </NTabPane>
          <NTabPane name="session" :tab="t('settings.tabs.session')">
            <SessionSettings />
          </NTabPane>
          <NTabPane name="privacy" :tab="t('settings.tabs.privacy')">
            <PrivacySettings />
          </NTabPane>
        </NTabs>
      </NSpin>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use "@/styles/variables" as *;

.settings-view {
  height: calc(100 * var(--vh));
  display: flex;
  flex-direction: column;
}

.settings-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}
</style>
