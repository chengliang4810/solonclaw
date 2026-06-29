<script setup lang="ts">
import { startCodexLogin, pollCodexLogin } from '@/api/solonclaw/codex-auth'
import DeviceCodeLoginModal from './DeviceCodeLoginModal.vue'

const emit = defineEmits<{ close: []; success: [] }>()

const textKeys = {
  title: 'models.codexLoginTitle',
  waiting: 'models.codexWaiting',
  copyCode: 'models.codexCopyCode',
  openLink: 'models.codexOpenLink',
  approved: 'models.codexApproved',
  expired: 'models.codexExpired',
} as const
</script>

<template>
  <DeviceCodeLoginModal
    :start-login="startCodexLogin"
    :poll-login="pollCodexLogin"
    :text-keys="textKeys"
    @close="emit('close')"
    @success="emit('success')"
  />
</template>
