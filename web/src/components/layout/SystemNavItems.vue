<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { SYSTEM_NAV_ITEMS } from '@/shared/sidebarNav'

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
