<script setup lang="ts">
import { useI18n } from 'vue-i18n'

defineProps<{
  readonly selectedKey: string
}>()

const emit = defineEmits<{
  navigate: [key: string]
}>()

const { t } = useI18n()

const SYSTEM_NAV_ITEMS = [
  {
    key: 'solonclaw.settings',
    labelKey: 'sidebar.settings',
    icon: '<circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />',
  },
  {
    key: 'solonclaw.diagnostics',
    labelKey: 'sidebar.diagnostics',
    icon: '<path d="M12 3v3" /><path d="M12 18v3" /><path d="M3 12h3" /><path d="M18 12h3" /><circle cx="12" cy="12" r="4" />',
  },
  {
    key: 'solonclaw.tuiRuntime',
    labelKey: 'sidebar.tuiRuntime',
    icon: '<rect x="3" y="4" width="18" height="14" rx="2" /><path d="M7 8l3 3-3 3" /><path d="M12 14h5" /><path d="M8 21h8" /><path d="M12 18v3" />',
  },
  {
    key: 'solonclaw.curator',
    labelKey: 'sidebar.curator',
    icon: '<path d="M4 5h16" /><path d="M4 12h16" /><path d="M4 19h10" /><circle cx="17" cy="19" r="2" />',
  },
  {
    key: 'solonclaw.mcp',
    labelKey: 'sidebar.mcp',
    icon: '<path d="M8 8h8v8H8z" /><path d="M4 12h4" /><path d="M16 12h4" /><path d="M12 4v4" /><path d="M12 16v4" /><circle cx="12" cy="12" r="1.5" />',
  },
] as const

function handleNav(key: string): void {
  emit('navigate', key)
}
</script>

<template>
  <button
    v-for="item in SYSTEM_NAV_ITEMS"
    :key="item.key"
    class="nav-item"
    :class="{ active: selectedKey === item.key }"
    @click="handleNav(item.key)"
  >
    <svg
      class="system-nav-icon"
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.5"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      v-html="item.icon"
    />
    <span>{{ t(item.labelKey) }}</span>
  </button>
</template>

<style scoped lang="scss">
.system-nav-icon {
  flex-shrink: 0;
}
</style>
