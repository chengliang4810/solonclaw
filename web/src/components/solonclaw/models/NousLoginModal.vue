<script setup lang="ts">
import { startNousLogin, pollNousLogin } from '@/api/solonclaw/nous-auth'
import DeviceCodeLoginModal from './DeviceCodeLoginModal.vue'

const emit = defineEmits<{ close: []; success: [] }>()

const textKeys = {
  title: 'models.nousLoginTitle',
  waiting: 'models.nousWaiting',
  copyCode: 'models.nousCopyCode',
  openLink: 'models.nousOpenLink',
  approved: 'models.nousApproved',
  expired: 'models.nousExpired',
  denied: 'models.nousDenied',
} as const
</script>

<template>
  <DeviceCodeLoginModal
    :start-login="startNousLogin"
    :poll-login="pollNousLogin"
    :text-keys="textKeys"
    @close="emit('close')"
    @success="emit('success')"
  />
</template>
