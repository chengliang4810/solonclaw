<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Select, message } from 'antdv-next'
import type { SelectValue } from 'antdv-next'
import { useAgentsStore } from '@/stores/solonclaw/agents'

const props = defineProps<{
  sessionId?: string | null
}>()

const agentsStore = useAgentsStore()
const { t } = useI18n()

const options = computed(() =>
  [
    {
      label: t('agents.selectorDefault'),
      value: 'default',
      disabled: false,
    },
    ...agentsStore.agents.map(agent => ({
      label: agent.display_name && agent.display_name !== agent.name
        ? `${agent.display_name} (${agent.name})`
        : agent.name,
      value: agent.name,
      disabled: !agent.enabled,
    })),
  ],
)

async function load() {
  await agentsStore.fetchAgents(props.sessionId || undefined)
}

async function handleChange(value: SelectValue) {
  if (typeof value !== 'string' || value === agentsStore.activeAgentName) return
  if (!props.sessionId) {
    message.warning(t('agents.selectSessionFirst'))
    return
  }
  try {
    await agentsStore.activateAgent(value, props.sessionId)
    message.success(t('agents.activateSuccess', { name: value }))
  } catch (err: any) {
    message.error(err?.message || t('agents.activateFailed'))
  }
}

onMounted(load)
watch(() => props.sessionId, load)
</script>

<template>
  <div class="agent-selector">
    <span class="agent-selector-label">{{ t('agents.selectorLabel') }}</span>
    <Select
      :value="agentsStore.activeAgentName"
      :options="options"
      :loading="agentsStore.loading || agentsStore.activating"
      size="small"
      class="agent-selector-control"
      @update:value="handleChange"
    />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.agent-selector {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.agent-selector-label {
  font-size: 12px;
  color: $text-muted;
  white-space: nowrap;
}

.agent-selector-control {
  width: 170px;
}

@media (max-width: $breakpoint-mobile) {
  .agent-selector-label {
    display: none;
  }

  .agent-selector-control {
    width: 130px;
  }
}
</style>
