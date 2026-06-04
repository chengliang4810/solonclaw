<script setup lang="ts">
import { onMounted } from 'vue'
import ChatPanel from '@/components/solonclaw/chat/ChatPanel.vue'
import { useAppStore } from '@/stores/solonclaw/app'
import { useChatStore } from '@/stores/solonclaw/chat'

const appStore = useAppStore()
const chatStore = useChatStore()

onMounted(async () => {
  appStore.loadModels()
  if (!chatStore.sessionsLoaded) {
    chatStore.loadSessions()
  } else if (!chatStore.isRunActive) {
    chatStore.refreshActiveSession()
  }
})
</script>

<template>
  <div class="chat-view">
    <ChatPanel />
  </div>
</template>

<style scoped lang="scss">
.chat-view {
  height: calc(100 * var(--vh));
  display: flex;
  flex-direction: column;
}
</style>
