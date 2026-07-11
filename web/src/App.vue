<script setup lang="ts">
import { onMounted, onUnmounted, computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { App as AntApp, ConfigProvider } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { getThemeConfig } from '@/styles/theme'
import { useTheme } from '@/composables/useTheme'
import AppSidebar from '@/components/layout/AppSidebar.vue'
import { useKeyboard } from '@/composables/useKeyboard'
import { useAppStore } from '@/stores/solonclaw/app'
import { useProfilesStore } from '@/stores/solonclaw/profiles'
import SessionSearchModal from '@/components/solonclaw/chat/SessionSearchModal.vue'

const { isDark } = useTheme()
const { t } = useI18n()
const appStore = useAppStore()
const profilesStore = useProfilesStore()
const route = useRoute()
const router = useRouter()
const ready = ref(false)
const profileRouteInitialized = ref(false)

const antdvTheme = computed(() => getThemeConfig(isDark.value))

const isLoginPage = computed(() => route.name === 'login')

const nodeVersionLow = computed(() => {
  const v = appStore.nodeVersion
  const major = parseInt(v.split('.')[0], 10)
  return !isNaN(major) && major < 23
})

// Close mobile sidebar on route change
watch(() => route.path, () => {
  appStore.closeSidebar()
})

watch(
  () => route.query.profile,
  (value) => {
    if (!profileRouteInitialized.value || typeof value === 'string') {
      profilesStore.setManagementProfile(typeof value === 'string' ? value : '')
    }
    profileRouteInitialized.value = true
  },
  { immediate: true },
)

watch(
  [() => route.path, () => profilesStore.managementProfile, ready],
  () => {
    if (!ready.value || isLoginPage.value) return
    const inUrl = typeof route.query.profile === 'string' ? route.query.profile : ''
    if (inUrl === profilesStore.managementProfile) return
    const query = { ...route.query }
    if (profilesStore.managementProfile) query.profile = profilesStore.managementProfile
    else delete query.profile
    void router.replace({ query })
  },
  { flush: 'post' },
)

// Wait for router to resolve before rendering layout
router.isReady().then(() => {
  ready.value = true
})

function syncAppRuntime() {
  if (!ready.value) {
    return
  }
  if (isLoginPage.value) {
    appStore.stopHealthPolling()
    return
  }
  appStore.loadModels()
  appStore.startHealthPolling()
  void profilesStore.initialize(typeof route.query.profile !== 'string').catch(() => {})
}

watch([isLoginPage, ready], syncAppRuntime)

onMounted(syncAppRuntime)

onUnmounted(() => {
  appStore.stopHealthPolling()
})

useKeyboard()
</script>

<template>
  <ConfigProvider :theme="antdvTheme">
    <AntApp>
      <div v-if="nodeVersionLow && ready" class="node-warning-bar">
        {{ t('sidebar.nodeVersionWarning', { version: appStore.nodeVersion }) }}
      </div>
      <div v-if="ready" class="app-layout" :class="{ 'no-sidebar': isLoginPage }">
        <button v-if="!isLoginPage" class="hamburger-btn" @click="appStore.toggleSidebar">
          <img src="/logo.png" alt="Menu" style="width: 24px; height: 24px;" />
        </button>
        <div v-if="!isLoginPage && appStore.sidebarOpen" class="mobile-backdrop" @click="appStore.closeSidebar" />
        <AppSidebar v-if="!isLoginPage" />
        <main class="app-main">
          <router-view :key="profilesStore.managementProfile || '__current_profile__'" />
        </main>
      </div>
      <SessionSearchModal />
    </AntApp>
  </ConfigProvider>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.app-layout {
  display: flex;
  height: calc(100 * var(--vh));
  width: 100vw;
  overflow: hidden;

  &.no-sidebar {
    display: block;
  }
}

.app-main {
  flex: 1;
  overflow-y: auto;
  background-color: $bg-primary;

  .no-sidebar & {
    height: calc(100 * var(--vh));
  }
}

.node-warning-bar {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  z-index: 100;
  padding: 4px 16px;
  font-size: 12px;
  font-weight: 500;
  color: #b45309;
  background-color: #fef3c7;
  border-bottom: 1px solid #fde68a;
  text-align: center;
  line-height: 1.4;
}
</style>
