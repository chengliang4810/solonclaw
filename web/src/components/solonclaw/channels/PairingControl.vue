<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Button, Input, Select, Spin, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import {
  approvePairing,
  clearPairingAdmin,
  fetchPairing,
  revokePairing,
  setPairingAdmin,
  type PairingPlatform,
} from '@/api/solonclaw/pairing'

const { t } = useI18n()
const platforms = ref<PairingPlatform[]>([])
const selected = ref('weixin')
const code = ref('')
const adminUserId = ref('')
const adminUserName = ref('')
const adminChatId = ref('')
const loading = ref(false)
const saving = ref(false)

const current = computed(() => platforms.value.find((item) => item.platform === selected.value))
const options = computed(() => platforms.value.map((item) => ({ label: item.platform, value: item.platform })))

onMounted(load)

async function load() {
  loading.value = true
  try {
    platforms.value = await fetchPairing()
  } catch (error: any) {
    message.error(error.message || t('channels.pairingLoadFailed'))
  } finally {
    loading.value = false
  }
}

async function approve() {
  if (!code.value.trim()) return
  await mutate(async () => {
    await approvePairing(selected.value, code.value.trim())
    code.value = ''
  }, t('channels.pairingApproved'))
}

async function revoke(userId: string) {
  await mutate(() => revokePairing(selected.value, userId), t('channels.pairingRevoked'))
}

async function setAdmin() {
  if (!adminUserId.value.trim()) return
  await mutate(async () => {
    await setPairingAdmin(selected.value, adminUserId.value.trim(), adminUserName.value.trim(), adminChatId.value.trim())
    adminUserId.value = ''
    adminUserName.value = ''
    adminChatId.value = ''
  }, t('channels.pairingAdminSaved'))
}

async function clearAdmin() {
  await mutate(() => clearPairingAdmin(selected.value), t('channels.pairingAdminCleared'))
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
        <h3>{{ t('channels.pairingTitle') }}</h3>
        <p>{{ t('channels.pairingDescription') }}</p>
      </div>
      <Button size="small" :loading="loading" @click="load">{{ t('channels.mediaRefresh') }}</Button>
    </div>

    <Select v-model:value="selected" size="small" class="platform-select" :options="options" />
    <Spin :spinning="loading || saving" size="small">
      <div v-if="current" class="pairing-body">
        <div class="admin-line">
          <span>{{ t('channels.pairingAdmin') }}</span>
          <strong>{{ current.admin?.user_name || current.admin?.user_id || t('channels.pairingUnset') }}</strong>
          <Button v-if="current.admin" size="small" danger @click="clearAdmin">{{ t('channels.pairingClearAdmin') }}</Button>
        </div>

        <div class="form-row admin-form">
          <Input v-model:value="adminUserId" size="small" :placeholder="t('channels.pairingAdminUserId')" />
          <Input v-model:value="adminUserName" size="small" :placeholder="t('channels.pairingAdminUserName')" />
          <Input v-model:value="adminChatId" size="small" :placeholder="t('channels.pairingAdminChatId')" />
          <Button size="small" :disabled="!adminUserId.trim()" @click="setAdmin">{{ t('channels.pairingSetAdmin') }}</Button>
        </div>

        <div class="form-row approve-form">
          <Input v-model:value="code" size="small" :placeholder="t('channels.pairingCode')" @press-enter="approve" />
          <Button size="small" type="primary" :disabled="!code.trim()" @click="approve">{{ t('channels.pairingApprove') }}</Button>
        </div>

        <div class="user-columns">
          <div>
            <h4>{{ t('channels.pairingPending') }} ({{ current.pending.length }})</h4>
            <p v-if="!current.pending.length" class="empty-text">{{ t('channels.pairingEmpty') }}</p>
            <div v-for="user in current.pending" :key="user.user_id" class="user-line">
              <span>{{ user.user_name || user.user_id }}</span>
              <small>{{ user.user_id }}</small>
            </div>
          </div>
          <div>
            <h4>{{ t('channels.pairingApprovedUsers') }} ({{ current.approved.length }})</h4>
            <p v-if="!current.approved.length" class="empty-text">{{ t('channels.pairingEmpty') }}</p>
            <div v-for="user in current.approved" :key="user.user_id" class="user-line">
              <span>{{ user.user_name || user.user_id }}</span>
              <small>{{ user.user_id }}</small>
              <Button size="small" danger @click="revoke(user.user_id)">{{ t('channels.pairingRevoke') }}</Button>
            </div>
          </div>
        </div>
      </div>
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

.section-head,
.admin-line,
.user-line {
  display: flex;
  align-items: center;
  gap: 12px;
}

.section-head {
  justify-content: space-between;
  margin-bottom: 12px;
}

h3,
h4,
p {
  margin: 0;
}

h3 { font-size: 15px; }
h4 { margin-bottom: 8px; font-size: 13px; }
p, small { color: $text-muted; }

.platform-select { width: 180px; margin-bottom: 12px; }
.pairing-body { display: grid; gap: 12px; }
.admin-line strong { flex: 1; }
.form-row { display: grid; gap: 8px; }
.admin-form { grid-template-columns: repeat(3, minmax(0, 1fr)) auto; }
.approve-form { grid-template-columns: minmax(0, 1fr) auto; }
.user-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
.user-line { min-height: 34px; border-bottom: 1px solid $border-light; }
.user-line span { min-width: 0; overflow-wrap: anywhere; }
.user-line small { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.empty-text { padding: 8px 0; }

@media (max-width: 900px) {
  .admin-form,
  .user-columns { grid-template-columns: 1fr; }
}
</style>
