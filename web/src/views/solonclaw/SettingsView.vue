<script setup lang="ts">
import { onMounted } from "vue";
import {
  Tabs,
  TabPane,
  Spin,
} from "antdv-next";
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
      <Spin
        :open="settingsStore.loading || settingsStore.saving"
        size="large"
        :description="t('common.loading')"
      >
        <Tabs type="line" animated>
          <TabPane name="account" :tab="t('settings.tabs.account')">
            <AccountSettings />
          </TabPane>
          <TabPane name="display" :tab="t('settings.tabs.display')">
            <DisplaySettings />
          </TabPane>
          <TabPane name="agent" :tab="t('settings.tabs.agent')">
            <AgentSettings />
          </TabPane>
          <TabPane name="models" :tab="t('settings.tabs.models')">
            <ModelSettings />
          </TabPane>
          <TabPane name="memory" :tab="t('settings.tabs.memory')">
            <MemorySettings />
          </TabPane>
          <TabPane name="session" :tab="t('settings.tabs.session')">
            <SessionSettings />
          </TabPane>
          <TabPane name="privacy" :tab="t('settings.tabs.privacy')">
            <PrivacySettings />
          </TabPane>
        </Tabs>
      </Spin>
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
