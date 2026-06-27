<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Button } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { fetchConfigDiagnostics, fetchRawConfig, type ConfigDiagnostics } from '@/api/solonclaw/config'

const { t } = useI18n()
const diagnostics = ref<ConfigDiagnostics | null>(null)
const rawConfig = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const [diagnosticsData, rawData] = await Promise.all([fetchConfigDiagnostics(), fetchRawConfig()])
    diagnostics.value = diagnosticsData
    rawConfig.value = rawData.yaml || ''
  } finally {
    loading.value = false
  }
}

function text(value: unknown) {
  if (value === undefined || value === null || value === '') return '-'
  if (typeof value === 'object') return JSON.stringify(value, null, 2)
  return String(value)
}

onMounted(load)
</script>

<template>
  <section class="config-diagnostics">
    <div class="section-head">
      <div>
        <h3>{{ t('settings.configDiagnostics.title') }}</h3>
        <p>{{ t('settings.configDiagnostics.description') }}</p>
      </div>
      <Button size="small" :loading="loading" @click="load">{{ t('settings.configDiagnostics.refresh') }}</Button>
    </div>
    <section class="raw-config">
      <h4>{{ t('settings.configDiagnostics.rawTitle') }}</h4>
      <pre>{{ rawConfig || t('settings.configDiagnostics.rawEmpty') }}</pre>
    </section>
    <div v-if="diagnostics" class="diagnostics-list">
      <article v-for="(value, key) in diagnostics" :key="String(key)">
        <strong>{{ key }}</strong>
        <pre>{{ text(value) }}</pre>
      </article>
    </div>
    <div v-else class="empty-state">{{ loading ? t('common.loading') : t('settings.configDiagnostics.empty') }}</div>
  </section>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.config-diagnostics {
  max-width: 960px;
}

.section-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 16px;
}

.section-head h3 {
  margin: 0;
  font-size: 16px;
  color: $text-primary;
}

.section-head p {
  margin: 6px 0 0;
  font-size: 13px;
  color: $text-muted;
}

.diagnostics-list {
  display: grid;
  gap: 10px;
}

.raw-config {
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  background: $bg-secondary;
  padding: 12px;
  margin-bottom: 14px;
}

.raw-config h4 {
  margin: 0 0 8px;
  color: $text-primary;
  font-size: 13px;
}

.raw-config pre {
  margin: 0;
  max-height: 360px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  color: $text-secondary;
  font-family: $font-code;
  font-size: 12px;
}

.diagnostics-list article {
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  background: $bg-secondary;
  padding: 12px;
}

.diagnostics-list strong {
  display: block;
  margin-bottom: 8px;
  font-size: 13px;
  color: $text-primary;
}

.diagnostics-list pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  color: $text-secondary;
  font-family: $font-code;
}

.empty-state {
  padding: 48px 0;
  text-align: center;
  color: $text-muted;
  font-size: 13px;
}
</style>
