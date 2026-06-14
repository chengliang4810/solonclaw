<script setup lang="ts">
import { defineAsyncComponent, h, onMounted } from 'vue'
import { useAppStore } from '@/stores/solonclaw/app'
import { useChatStore } from '@/stores/solonclaw/chat'

const appStore = useAppStore()
const chatStore = useChatStore()
const ChatPanel = defineAsyncComponent({
  loader: () => import('@/components/solonclaw/chat/ChatPanel.vue'),
  delay: 120,
  timeout: 20000,
  loadingComponent: {
    name: 'ChatPanelLoading',
    setup() {
      return () => h('div', { class: 'chat-panel-state' }, '对话加载中...')
    },
  },
  errorComponent: {
    name: 'ChatPanelLoadError',
    props: {
      error: Object,
    },
    setup(props) {
      return () => h('div', { class: 'chat-panel-state chat-panel-state--error' }, [
        h('strong', '对话加载失败'),
        h('span', props.error instanceof Error ? props.error.message : '请刷新后重试。'),
      ])
    },
  },
})

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

.chat-panel-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 8px;
  color: var(--text-muted);
  background: var(--bg-card);
}

.chat-panel-state--error {
  color: var(--error);
}
</style>
