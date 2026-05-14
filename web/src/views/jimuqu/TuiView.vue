<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { createElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { TuiApp } from '@/tui/TuiApp'

const hostRef = ref<HTMLDivElement | null>(null)
let root: Root | null = null

onMounted(() => {
  if (!hostRef.value) return
  root = createRoot(hostRef.value)
  root.render(createElement(TuiApp))
})

onUnmounted(() => {
  root?.unmount()
  root = null
})
</script>

<template>
  <div ref="hostRef" class="react-tui-host" />
</template>

<style scoped>
.react-tui-host {
  height: 100%;
  min-height: 0;
}
</style>
