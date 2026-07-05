<script setup lang="ts">
import { Popconfirm } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import type { Session } from '@/stores/solonclaw/chat'
import { formatTimestampMs } from '@/shared/session-display'

const props = defineProps<{
  session: Session
  active: boolean
  pinned: boolean
  canDelete: boolean
}>()

const emit = defineEmits<{
  select: []
  contextmenu: [event: MouseEvent]
  delete: []
}>()

const { t } = useI18n()
</script>

<template>
  <div
    class="session-item"
    :class="{ active }"
    role="button"
    tabindex="0"
    :aria-current="active ? 'page' : undefined"
    @click="emit('select')"
    @contextmenu="emit('contextmenu', $event)"
    @keydown.enter.prevent="emit('select')"
    @keydown.space.prevent="emit('select')"
  >
    <div class="session-item-content">
      <span class="session-item-title-row">
        <span v-if="pinned" class="session-item-pin" aria-hidden="true">
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 17v5" />
            <path d="M5 8l14 0" />
            <path d="M8 3l8 0 0 5 3 5-14 0 3-5z" />
          </svg>
        </span>
        <span class="session-item-title">{{ session.title }}</span>
        <span v-if="session.isLive" class="session-item-live">{{ t('chat.liveMode') }}</span>
      </span>
      <span class="session-item-meta">
        <span v-if="session.model" class="session-item-model">{{ session.model }}</span>
        <span class="session-item-time">{{ formatTimestampMs(session.createdAt) }}</span>
      </span>
    </div>
    <Popconfirm
      v-if="canDelete"
      :title="t('chat.deleteSession')"
      :ok-text="t('common.delete')"
      :cancel-text="t('common.cancel')"
      @confirm="emit('delete')"
    >
      <button type="button" class="session-item-delete" @click.stop>
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </button>
    </Popconfirm>
  </div>
</template>
