<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
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
import { fetchConfigDiagnostics, fetchConfigSchema, fetchRawConfig } from "@/api/solonclaw/config";

const settingsStore = useSettingsStore();
const { t } = useI18n();
const activeTab = ref("account");
const configDiagnostics = ref<Record<string, any> | null>(null);
const configSchema = ref<Record<string, any> | null>(null);
const rawConfig = ref<Record<string, any> | null>(null);
const configInfoLoading = ref(false);

onMounted(() => {
  settingsStore.fetchSettings();
});

watch(activeTab, (tab) => {
  if (tab === "configDiagnostics") {
    loadConfigInfo();
  }
});

async function loadConfigInfo() {
  if (configDiagnostics.value || configSchema.value || rawConfig.value) return;
  configInfoLoading.value = true;
  try {
    const [diagnostics, schema, raw] = await Promise.all([
      fetchConfigDiagnostics(),
      fetchConfigSchema(),
      fetchRawConfig(),
    ]);
    configDiagnostics.value = diagnostics;
    configSchema.value = schema;
    rawConfig.value = raw;
  } finally {
    configInfoLoading.value = false;
  }
}

function jsonText(value: unknown) {
  return value ? JSON.stringify(value, null, 2) : "";
}
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
        <Tabs v-model:activeKey="activeTab" type="card" animated>
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
          <TabPane tabKey="configDiagnostics" :tab="t('settings.tabs.configDiagnostics')">
            <Spin :spinning="configInfoLoading">
              <div class="config-diagnostics">
                <section>
                  <h3>{{ t("settings.configDiagnostics.diagnostics") }}</h3>
                  <pre>{{ jsonText(configDiagnostics) }}</pre>
                </section>
                <section>
                  <h3>{{ t("settings.configDiagnostics.schema") }}</h3>
                  <pre>{{ jsonText(configSchema) }}</pre>
                </section>
                <section>
                  <h3>{{ t("settings.configDiagnostics.raw") }}</h3>
                  <pre>{{ jsonText(rawConfig) }}</pre>
                </section>
              </div>
            </Spin>
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

.config-diagnostics {
  display: grid;
  gap: 12px;
}

.config-diagnostics section {
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  background: $bg-primary;
  padding: 12px;
}

.config-diagnostics h3 {
  margin: 0 0 8px;
  font-size: 14px;
}

.config-diagnostics pre {
  margin: 0;
  max-height: 260px;
  overflow: auto;
  border-radius: $radius-sm;
  background: rgba($bg-secondary, 0.72);
  color: $text-primary;
  padding: 10px;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
