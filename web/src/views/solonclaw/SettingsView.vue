<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import {
  Button,
  Tabs,
  TabPane,
  Spin,
  TextArea,
  message,
} from "antdv-next";
import { useI18n } from "vue-i18n";
import { useSettingsStore } from "@/stores/solonclaw/settings";
import DisplaySettings from "@/components/solonclaw/settings/DisplaySettings.vue";
import AgentSettings from "@/components/solonclaw/settings/AgentSettings.vue";
import GatewaySettings from "@/components/solonclaw/settings/GatewaySettings.vue";
import SessionSettings from "@/components/solonclaw/settings/SessionSettings.vue";
import AccountSettings from "@/components/solonclaw/settings/AccountSettings.vue";
import { fetchConfigDefaults, fetchConfigDiagnostics, fetchConfigSchema, fetchRawConfig, saveRawConfig } from "@/api/solonclaw/config";

const settingsStore = useSettingsStore();
const { t } = useI18n();
const activeTab = ref("account");
const configDiagnostics = ref<Record<string, unknown> | null>(null);
const configSchema = ref<Record<string, unknown> | null>(null);
const configDefaults = ref<Record<string, unknown> | null>(null);
const rawConfig = ref<Record<string, unknown> | null>(null);
const rawConfigText = ref("");
const configInfoLoading = ref(false);
const rawConfigSaving = ref(false);

onMounted(() => {
  settingsStore.fetchSettings();
});

watch(activeTab, (tab) => {
  if (tab === "configDiagnostics") {
    loadConfigInfo();
  }
});

async function loadConfigInfo(force = false) {
  if (!force && (configDiagnostics.value || configSchema.value || configDefaults.value || rawConfig.value)) return;
  configInfoLoading.value = true;
  try {
    const [diagnostics, schema, defaults, raw] = await Promise.all([
      fetchConfigDiagnostics(),
      fetchConfigSchema(),
      fetchConfigDefaults(),
      fetchRawConfig(),
    ]);
    configDiagnostics.value = diagnostics;
    configSchema.value = schema;
    configDefaults.value = defaults;
    rawConfig.value = raw;
    rawConfigText.value = jsonText(raw);
  } finally {
    configInfoLoading.value = false;
  }
}

async function handleSaveRawConfig() {
  rawConfigSaving.value = true;
  try {
    await saveRawConfig(rawConfigText.value);
    await loadConfigInfo(true);
    message.success(t("settings.configDiagnostics.rawSaved"));
  } catch (err) {
    message.error(err instanceof Error ? err.message : t("settings.configDiagnostics.rawSaveFailed"));
  } finally {
    rawConfigSaving.value = false;
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
        <div v-if="settingsStore.loadError" class="settings-load-error">
          <strong>{{ t('common.fetchFailed') }}</strong>
          <span>{{ settingsStore.loadError }}</span>
        </div>

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
          <TabPane tabKey="gateway" :tab="t('settings.tabs.gateway')">
            <GatewaySettings />
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
                  <h3>{{ t("settings.configDiagnostics.defaults") }}</h3>
                  <pre>{{ jsonText(configDefaults) }}</pre>
                </section>
                <section>
                  <div class="config-section-title">
                    <h3>{{ t("settings.configDiagnostics.raw") }}</h3>
                    <div class="config-section-actions">
                      <Button size="small" :loading="configInfoLoading" @click="loadConfigInfo(true)">
                        {{ t("settings.configDiagnostics.refresh") }}
                      </Button>
                      <Button type="primary" size="small" :loading="rawConfigSaving" @click="handleSaveRawConfig">
                        {{ t("settings.configDiagnostics.saveRaw") }}
                      </Button>
                    </div>
                  </div>
                  <TextArea
                    v-model:value="rawConfigText"
                    class="config-raw-editor"
                    :auto-size="{ minRows: 12, maxRows: 24 }"
                  />
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

.settings-load-error {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid rgba(220, 38, 38, 0.32);
  border-radius: $radius-sm;
  background: rgba(220, 38, 38, 0.08);
  color: $text-primary;
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

.config-section-title h3 {
  margin: 0;
}

.config-section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.config-section-actions {
  display: flex;
  gap: 8px;
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

.config-raw-editor {
  font-family: ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", monospace;
  font-size: 12px;
  line-height: 1.5;
}
</style>
