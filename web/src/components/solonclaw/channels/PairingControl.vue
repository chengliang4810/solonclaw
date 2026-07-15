<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Button, Input, Select, Spin, Tag, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import {
  claimPairingOwner,
  fetchPairingChannelStates,
  fetchPairing,
  retryPairingWelcome,
  setPrimaryNotificationChannel,
  type PairingChannelState,
  type PairingPlatform,
} from '@/api/solonclaw/pairing'

const props = defineProps<{
  /** Dashboard 当前管理的 Profile，即当前渠道机器人账号所属角色。 */
  profileName: string
  /** 已配置平台的连接开关快照。 */
  platformSettings: Record<string, { enabled?: boolean }>
  /** 平台代码对应的展示名称。 */
  platformLabels: Record<string, string>
}>()

const { t } = useI18n()
const platforms = ref<PairingPlatform[]>([])
const channelStates = ref<Record<string, PairingChannelState>>({})
const selected = ref('')
const code = ref('')
const loading = ref(false)
const saving = ref(false)
const welcomeFailures = ref<Record<string, boolean>>({})

const current = computed(() => platforms.value.find((item) => item.platform === selected.value))
const options = computed(() => platforms.value.map((item) => ({
  label: props.platformLabels[item.platform] || item.platform,
  value: item.platform,
})))
const channelState = computed<PairingChannelState>(() =>
  channelStates.value[selected.value]
    || (props.platformSettings[selected.value]?.enabled ? 'disconnected' : 'disabled'),
)
const channelStateColor = computed(() => channelState.value === 'connected' ? 'success' : 'default')
const channelStateLabel = computed(() => t(`channels.channelState${channelState.value[0].toUpperCase()}${channelState.value.slice(1)}`))
const ownerName = computed(() => current.value?.admin?.user_name || current.value?.admin?.user_id || '')
const notificationChat = computed(() => current.value?.home_channel?.chat_id || '')
const isPrimaryNotificationChannel = computed(() => current.value?.home_channel?.primary === true)
const canSetPrimaryNotificationChannel = computed(() =>
  !!current.value?.admin && !!current.value?.home_channel && !isPrimaryNotificationChannel.value,
)
const welcomeFailed = computed(() => welcomeFailures.value[selected.value] === true)
const canRetryWelcome = computed(() => !!current.value?.admin)

onMounted(load)

watch(platforms, items => {
  if (!items.some(item => item.platform === selected.value)) {
    selected.value = items[0]?.platform || ''
  }
})

watch(() => props.profileName, () => {
  selected.value = ''
  welcomeFailures.value = {}
  void load()
})

async function load() {
  loading.value = true
  try {
    platforms.value = await fetchPairing()
    channelStates.value = await fetchPairingChannelStates().catch(() => ({}))
  } catch (error: any) {
    message.error(error.message || t('channels.pairingLoadFailed'))
  } finally {
    loading.value = false
  }
}

async function claimOwner() {
  if (!code.value.trim()) return
  saving.value = true
  try {
    const result = await claimPairingOwner(selected.value, code.value.trim())
    code.value = ''
    welcomeFailures.value[selected.value] = result.welcome_delivery?.status === 'failed'
    await load()
    message[welcomeFailed.value ? 'warning' : 'success'](
      t(welcomeFailed.value ? 'channels.pairingOwnerBoundWelcomeFailed' : 'channels.pairingOwnerBound'),
    )
  } catch (error: any) {
    message.error(error.message || t('channels.pairingSaveFailed'))
  } finally {
    saving.value = false
  }
}

async function retryWelcome() {
  if (!current.value?.platform) return
  saving.value = true
  try {
    const result = await retryPairingWelcome(current.value.platform)
    welcomeFailures.value[current.value.platform] = result.welcome_delivery?.status === 'failed'
    message[welcomeFailed.value ? 'error' : 'success'](
      t(welcomeFailed.value ? 'channels.pairingWelcomeRetryFailed' : 'channels.pairingWelcomeRetried'),
    )
  } catch (error: any) {
    message.error(error.message || t('channels.pairingWelcomeRetryFailed'))
  } finally {
    saving.value = false
  }
}

async function setPrimaryChannel() {
  if (!current.value?.platform) return
  await mutate(
    () => setPrimaryNotificationChannel(current.value!.platform),
    t('channels.primaryNotificationSaved'),
  )
}

async function mutate(action: () => Promise<unknown>, success: string) {
  saving.value = true
  try {
    await action()
    await load()
    message.success(success)
  } catch (error: any) {
    message.error(error.message || t('channels.pairingSaveFailed'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="pairing-control">
    <div class="section-head">
      <div>
        <h3>{{ t('channels.personalBindingTitle') }}</h3>
        <p>{{ t('channels.personalBindingDescription') }}</p>
      </div>
      <Button size="small" :loading="loading" @click="load">{{ t('channels.mediaRefresh') }}</Button>
    </div>

    <Select v-model:value="selected" size="small" class="platform-select" :options="options" />
    <Spin :spinning="loading || saving" size="small">
      <div v-if="current" class="pairing-body">
        <div class="status-grid">
          <div class="status-item">
            <span>{{ t('channels.botProfile') }}</span>
            <strong>{{ profileName }}</strong>
          </div>
          <div class="status-item">
            <span>{{ t('channels.channelConnection') }}</span>
            <Tag :color="channelStateColor" size="small">{{ channelStateLabel }}</Tag>
          </div>
          <div class="status-item">
            <span>{{ t('channels.pairingAdmin') }}</span>
            <strong>{{ ownerName || t('channels.pairingUnset') }}</strong>
          </div>
          <div class="status-item">
            <span>{{ t('channels.defaultNotificationDm') }}</span>
            <div class="notification-value">
              <strong>{{ notificationChat || t('channels.pairingUnset') }}</strong>
              <Tag v-if="isPrimaryNotificationChannel" color="success" size="small">{{ t('channels.primaryNotificationChannel') }}</Tag>
            </div>
          </div>
        </div>

        <Button
          v-if="canSetPrimaryNotificationChannel"
          size="small"
          type="primary"
          @click="setPrimaryChannel"
        >
          {{ t('channels.setPrimaryNotificationChannel') }}
        </Button>

        <div v-if="canRetryWelcome" :class="['welcome-action', { 'welcome-failure': welcomeFailed }]">
          <span v-if="welcomeFailed">{{ t('channels.pairingWelcomeFailed') }}</span>
          <Button size="small" @click="retryWelcome">{{ t('channels.pairingWelcomeRetry') }}</Button>
        </div>

        <div v-if="!current.admin" class="owner-claim">
          <p>{{ t('channels.personalBindingHint') }}</p>
          <div class="form-row claim-form">
            <Input v-model:value="code" size="small" :placeholder="t('channels.personalBindingCode')" @press-enter="claimOwner" />
            <Button size="small" type="primary" :disabled="!code.trim()" @click="claimOwner">{{ t('channels.personalBindingAction') }}</Button>
          </div>
        </div>
      </div>
      <p v-else class="empty-text">{{ t('channels.pairingEmpty') }}</p>
    </Spin>
  </section>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.pairing-control {
  margin-top: 20px;
  padding: 16px;
  border: 1px solid $border-color;
  border-radius: $radius-md;
  background: $bg-card;
}

.section-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.section-head {
  justify-content: space-between;
  margin-bottom: 12px;
}

h3,
p {
  margin: 0;
}

h3 { font-size: 15px; }
p { color: $text-muted; }

.platform-select { width: 180px; margin-bottom: 12px; }
.pairing-body { display: grid; gap: 12px; }
.welcome-action { display: flex; align-items: center; gap: 8px; }
.welcome-failure { color: $error; }
.form-row { display: grid; gap: 8px; }
.claim-form { grid-template-columns: minmax(0, 1fr) auto; }
.status-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.status-item { display: grid; gap: 4px; min-width: 0; padding: 10px 12px; border: 1px solid $border-light; }
.status-item span { color: $text-muted; font-size: 12px; }
.status-item strong { min-width: 0; overflow-wrap: anywhere; }
.notification-value { display: flex; align-items: center; gap: 8px; min-width: 0; }
.notification-value strong { flex: 1; }
.owner-claim { display: grid; gap: 8px; }
.empty-text { padding: 8px 0; }

@media (max-width: 900px) {
  .status-grid,
  .claim-form { grid-template-columns: 1fr; }
}
</style>
