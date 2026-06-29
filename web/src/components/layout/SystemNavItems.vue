<script setup lang="ts">
import { useI18n } from 'vue-i18n'

defineProps<{
  readonly selectedKey: string
}>()

const emit = defineEmits<{
  navigate: [key: string]
}>()

const { t } = useI18n()

function handleNav(key: string): void {
  emit('navigate', key)
}
</script>

<template>
  <button class="nav-item" :class="{ active: selectedKey === 'solonclaw.settings' }" @click="handleNav('solonclaw.settings')">
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </svg>
    <span>{{ t("sidebar.settings") }}</span>
  </button>
  <button class="nav-item" :class="{ active: selectedKey === 'solonclaw.diagnostics' }" @click="handleNav('solonclaw.diagnostics')">
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
      <path d="M12 3v3" />
      <path d="M12 18v3" />
      <path d="M3 12h3" />
      <path d="M18 12h3" />
      <circle cx="12" cy="12" r="4" />
    </svg>
    <span>{{ t("sidebar.diagnostics") }}</span>
  </button>
  <button class="nav-item" :class="{ active: selectedKey === 'solonclaw.tuiRuntime' }" @click="handleNav('solonclaw.tuiRuntime')">
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
      <rect x="3" y="4" width="18" height="14" rx="2" />
      <path d="M7 8l3 3-3 3" />
      <path d="M12 14h5" />
      <path d="M8 21h8" />
      <path d="M12 18v3" />
    </svg>
    <span>终端运行时</span>
  </button>
  <button class="nav-item" :class="{ active: selectedKey === 'solonclaw.curator' }" @click="handleNav('solonclaw.curator')">
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
      <path d="M4 5h16" />
      <path d="M4 12h16" />
      <path d="M4 19h10" />
      <circle cx="17" cy="19" r="2" />
    </svg>
    <span>{{ t("sidebar.curator") }}</span>
  </button>
  <button class="nav-item" :class="{ active: selectedKey === 'solonclaw.mcp' }" @click="handleNav('solonclaw.mcp')">
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
      <path d="M8 8h8v8H8z" />
      <path d="M4 12h4" />
      <path d="M16 12h4" />
      <path d="M12 4v4" />
      <path d="M12 16v4" />
      <circle cx="12" cy="12" r="1.5" />
    </svg>
    <span>{{ t("sidebar.mcp") }}</span>
  </button>
</template>
