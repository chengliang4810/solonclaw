<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { Button, Modal, Spin, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { isRecord } from '@/shared/text'
import { copyToClipboard } from '@/utils/clipboard'

type DeviceLoginStatus = 'idle' | 'loading' | 'waiting' | 'approved' | 'expired' | 'error'
type DeviceLoginPollStatus = 'pending' | 'approved' | 'denied' | 'expired' | 'error'

interface DeviceCodeStartResult {
  readonly session_id: string
  readonly user_code: string
  readonly verification_url: string
}

interface DeviceCodePollResult {
  readonly status: DeviceLoginPollStatus
  readonly error: string | null
}

interface DeviceCodeLoginTextKeys {
  readonly title: string
  readonly waiting: string
  readonly copyCode: string
  readonly openLink: string
  readonly approved: string
  readonly expired: string
  readonly denied?: string
}

const POLL_DELAY_MS = 3000
const SUCCESS_CLOSE_DELAY_MS = 1000
const SUCCESS_EMIT_DELAY_MS = 200

const props = defineProps<{
  startLogin: () => Promise<DeviceCodeStartResult>
  pollLogin: (sessionId: string) => Promise<DeviceCodePollResult>
  textKeys: DeviceCodeLoginTextKeys
}>()

const emit = defineEmits<{
  close: []
  success: []
}>()

const { t } = useI18n()
const showModal = ref(true)
const status = ref<DeviceLoginStatus>('idle')
const userCode = ref('')
const verificationUrl = ref('')
const sessionId = ref('')
const errorMessage = ref('')
let pollTimer: ReturnType<typeof setTimeout> | null = null

onMounted(() => {
  startLogin()
})

onUnmounted(() => {
  stopPolling()
})

async function startLogin() {
  status.value = 'loading'
  errorMessage.value = ''

  try {
    const data = await props.startLogin()
    userCode.value = data.user_code
    verificationUrl.value = data.verification_url
    sessionId.value = data.session_id
    status.value = 'waiting'
    startPolling()
  } catch (error: unknown) {
    status.value = 'error'
    errorMessage.value = loginErrorMessage(error)
    message.error(errorMessage.value)
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setTimeout(async () => {
    try {
      const result = await props.pollLogin(sessionId.value)
      handlePollResult(result)
    } catch (pollError: unknown) {
      if (!(pollError instanceof Error)) {
        throw pollError
      }
      startPolling()
    }
  }, POLL_DELAY_MS)
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function handlePollResult(result: DeviceCodePollResult) {
  if (result.status === 'pending') {
    startPolling()
    return
  }
  if (result.status === 'approved') {
    status.value = 'approved'
    message.success(t(props.textKeys.approved))
    setTimeout(() => {
      showModal.value = false
      setTimeout(() => emit('success'), SUCCESS_EMIT_DELAY_MS)
    }, SUCCESS_CLOSE_DELAY_MS)
    return
  }
  if (result.status === 'expired') {
    status.value = 'expired'
    return
  }
  if (result.status === 'denied') {
    status.value = 'error'
    errorMessage.value = props.textKeys.denied ? t(props.textKeys.denied) : (result.error || 'Authorization denied')
    return
  }
  status.value = 'error'
  errorMessage.value = result.error || 'Unknown error'
}

function handleClose() {
  stopPolling()
  showModal.value = false
}

async function copyCode() {
  const ok = await copyToClipboard(userCode.value)
  if (ok) {
    message.success(t(props.textKeys.copyCode))
  } else {
    message.error(t(props.textKeys.copyCode) + ' ✗')
  }
}

function openLink() {
  window.open(verificationUrl.value, '_blank')
}

function retry() {
  status.value = 'idle'
  userCode.value = ''
  verificationUrl.value = ''
  sessionId.value = ''
  errorMessage.value = ''
  startLogin()
}

function loginErrorMessage(error: unknown): string {
  const text = error instanceof Error ? error.message : String(error || '')
  const match = text.match(/\{[\s\S]*\}$/)
  if (!match) return text
  try {
    const body: unknown = JSON.parse(match[0])
    return isRecord(body) && typeof body.error === 'string' ? body.error : text
  } catch (parseError: unknown) {
    if (parseError instanceof SyntaxError) return text
    throw parseError
  }
}
</script>

<template>
  <Modal
    v-model:open="showModal"
    :title="t(textKeys.title)"
    :style="{ width: 'min(440px, calc(100vw - 32px))' }"
    :mask-closable="status !== 'waiting'"
    @after-leave="emit('close')"
  >
    <div class="device-login">
      <div v-if="status === 'idle' || status === 'loading'" class="device-login__state">
        <Spin size="small" />
      </div>

      <div v-else-if="status === 'waiting'" class="device-login__state">
        <p class="device-login__hint">{{ t(textKeys.waiting) }}</p>
        <div class="device-login__code" @click="copyCode">
          <span class="device-login__code-text">{{ userCode }}</span>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
        </div>
        <Button type="primary" block @click="openLink">
          <template #icon>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
          </template>
          {{ t(textKeys.openLink) }}
        </Button>
      </div>

      <div v-else-if="status === 'approved'" class="device-login__state device-login__state--success">
        <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>
        <p>{{ t(textKeys.approved) }}</p>
      </div>

      <div v-else-if="status === 'expired'" class="device-login__state">
        <p class="device-login__error">{{ t(textKeys.expired) }}</p>
        <Button size="small" @click="retry">{{ t('common.retry') }}</Button>
      </div>

      <div v-else-if="status === 'error'" class="device-login__state">
        <p class="device-login__error">{{ errorMessage }}</p>
        <Button size="small" @click="retry">{{ t('common.retry') }}</Button>
      </div>
    </div>

    <template #footer>
      <div class="modal-footer">
        <Button :disabled="status === 'waiting'" @click="handleClose">{{ t('common.cancel') }}</Button>
      </div>
    </template>
  </Modal>
</template>

<style scoped lang="scss" src="./DeviceCodeLoginModal.scss">
</style>
