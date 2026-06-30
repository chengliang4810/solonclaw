<script setup lang="ts">
import { Button, Spin } from 'antdv-next'
import { useI18n } from 'vue-i18n'

type ChannelQrUiStatus = 'idle' | 'loading' | 'waiting' | 'scaned' | 'confirmed' | 'error' | 'expired'

interface ChannelQrPanelState {
  readonly url: string
  readonly imageUrl: string
  readonly message: string
  readonly status: ChannelQrUiStatus
}

const props = withDefaults(defineProps<{
  state: ChannelQrPanelState
  domain?: string
  showEmptyStatus?: boolean
}>(), {
  domain: '',
  showEmptyStatus: false,
})

const emit = defineEmits<{
  start: []
}>()

const { t } = useI18n()

function canStartQrLogin(status: ChannelQrUiStatus) {
  return status === 'idle' || status === 'error' || status === 'expired' || status === 'confirmed'
}

function statusClass(status: ChannelQrUiStatus) {
  return status === 'error' || status === 'expired' ? 'error' : ''
}

function scanHint(status: ChannelQrUiStatus) {
  return status === 'scaned' ? t('platform.qrScanedHint') : t('platform.qrScanHint')
}

function statusFallbackMessage(status: ChannelQrUiStatus) {
  return status === 'expired' ? t('platform.qrExpired') : t('platform.qrFailed')
}

function shouldShowStandaloneStatus(state: ChannelQrPanelState, showEmptyStatus: boolean) {
  if (state.imageUrl) return false
  if (state.status === 'error' || state.status === 'expired') return true
  return showEmptyStatus && (state.status === 'waiting' || state.status === 'scaned')
}

function isHttpUrl(value: string) {
  return /^https?:\/\//i.test(value)
}
</script>

<template>
  <div class="channel-qr-section">
    <Button
      v-if="canStartQrLogin(state.status)"
      type="primary"
      size="small"
      @click="emit('start')"
    >
      {{ state.status === 'confirmed' ? t('platform.qrRelogin') : t('platform.qrLogin') }}
    </Button>
    <div v-if="state.status === 'loading'" class="channel-qr-loading">
      <Spin size="small" />
      <span>{{ t('platform.qrFetching') }}</span>
    </div>
    <div v-if="state.imageUrl" class="channel-qr-panel">
      <img class="channel-qr-image" :src="state.imageUrl" :alt="t('platform.qrLogin')" />
      <div class="channel-qr-caption" :class="statusClass(state.status)">
        {{ state.message || scanHint(state.status) }}
      </div>
      <div v-if="state.status === 'confirmed' && domain" class="channel-qr-caption">
        {{ domain }}
      </div>
      <a
        v-if="isHttpUrl(state.url)"
        class="channel-qr-link"
        :href="state.url"
        target="_blank"
        rel="noopener noreferrer"
      >
        {{ state.url }}
      </a>
    </div>
    <div v-if="shouldShowStandaloneStatus(state, showEmptyStatus)" :class="statusClass(state.status) ? 'channel-qr-error' : 'channel-qr-hint'">
      {{ state.status === 'error' || state.status === 'expired' ? (state.message || statusFallbackMessage(state.status)) : scanHint(state.status) }}
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.channel-qr-section {
  margin-top: 12px;
  margin-bottom: 12px;
}

.channel-qr-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  color: $text-muted;
  font-size: 13px;
}

.channel-qr-panel {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  margin-top: 10px;
}

.channel-qr-image {
  width: 192px;
  height: 192px;
  border: 1px solid $border-color;
  border-radius: 8px;
  background: #fff;
  padding: 8px;
}

.channel-qr-caption {
  font-size: 13px;
  color: $text-secondary;

  &.error {
    color: $error;
  }
}

.channel-qr-link {
  max-width: 100%;
  color: $accent-primary;
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
  word-break: break-all;
}

.channel-qr-hint {
  font-size: 13px;
  color: $text-secondary;
}

.channel-qr-error {
  margin-top: 8px;
  font-size: 13px;
  color: $error;
}
</style>
