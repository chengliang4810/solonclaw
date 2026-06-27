<script setup lang="ts">
import { onMounted, ref } from "vue";
import {
  Tabs,
  TabPane,
  Spin,
} from "antdv-next";
import { useI18n } from "vue-i18n";
import { useSettingsStore } from "@/stores/solonclaw/settings";
import DisplaySettings from "@/components/solonclaw/settings/DisplaySettings.vue";
import AgentSettings from "@/components/solonclaw/settings/AgentSettings.vue";
import SessionSettings from "@/components/solonclaw/settings/SessionSettings.vue";
import AccountSettings from "@/components/solonclaw/settings/AccountSettings.vue";

const settingsStore = useSettingsStore();
const { t } = useI18n();
const activeTab = ref("account");

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
        :spinning="settingsStore.loading || settingsStore.saving"
        size="large"
        :description="t('common.loading')"
      >
        <Tabs v-model:activeKey="activeTab" type="line" animated>
          <TabPane tabKey="account" :tab="t('settings.tabs.account')">
            <AccountSettings />
          </TabPane>
          <TabPane tabKey="display" :tab="t('settings.tabs.display')">
            <DisplaySettings />
          </TabPane>
          <TabPane tabKey="agent" :tab="t('settings.tabs.agent')">
            <AgentSettings />
          </TabPane>
          <TabPane tabKey="session" :tab="t('settings.tabs.session')">
            <SessionSettings />
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
