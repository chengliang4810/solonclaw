<script setup lang="ts">
import { computed } from 'vue'
import { Button, Select, Tooltip } from 'antdv-next'
import type { SelectValue } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useProfilesStore } from '@/stores/solonclaw/profiles'

const CURRENT_PROFILE_VALUE = '__dashboard_current__'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const profilesStore = useProfilesStore()

const visible = computed(() => profilesStore.profiles.length > 1)
const selectedValue = computed(() => profilesStore.managementProfile || CURRENT_PROFILE_VALUE)
const isOtherProfile = computed(() => !!profilesStore.managementProfile)
const options = computed(() => [
  {
    label: t('profiles.currentDashboard', { name: profilesStore.currentProfileName }),
    value: CURRENT_PROFILE_VALUE,
  },
  ...profilesStore.profiles
    .filter(profile => profile.name !== profilesStore.currentProfileName)
    .map(profile => ({ label: profile.name, value: profile.name })),
])

function selectProfile(value: SelectValue): void {
  if (typeof value !== 'string') return
  profilesStore.setManagementProfile(value === CURRENT_PROFILE_VALUE ? '' : value)
  syncRouteQuery()
}

function syncRouteQuery(): void {
  const query = { ...route.query }
  if (profilesStore.managementProfile) query.profile = profilesStore.managementProfile
  else delete query.profile
  void router.replace({ query })
}

function openProfiles(): void {
  void router.push({
    name: 'solonclaw.profiles',
    query: profilesStore.managementProfile ? { profile: profilesStore.managementProfile } : {},
  })
}
</script>

<template>
  <div v-if="visible" class="profile-switcher" :class="{ other: isOtherProfile }">
    <svg class="profile-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
      <circle cx="9" cy="7" r="4" />
      <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
      <path d="M16 3.13a4 4 0 0 1 0 7.75" />
    </svg>
    <Select
      class="profile-select"
      size="small"
      :value="selectedValue"
      :options="options"
      :loading="profilesStore.loading"
      :aria-label="t('profiles.managementTarget')"
      @update:value="selectProfile"
    />
    <Tooltip :title="t('profiles.manageProfiles')">
      <Button class="profile-manage" size="small" type="text" :aria-label="t('profiles.manageProfiles')" @click="openProfiles">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <circle cx="12" cy="12" r="3" />
          <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06-2.83 2.83-.06-.06A1.65 1.65 0 0 0 15 19.4a1.65 1.65 0 0 0-1 .6V21h-4v-1a1.65 1.65 0 0 0-1-.6 1.65 1.65 0 0 0-1.82.33l-.06.06-2.83-2.83.06-.06A1.65 1.65 0 0 0 4.6 15a1.65 1.65 0 0 0-.6-1H3v-4h1a1.65 1.65 0 0 0 .6-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06 2.83-2.83.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-.6V3h4v1a1.65 1.65 0 0 0 1 .6 1.65 1.65 0 0 0 1.82-.33l.06-.06 2.83 2.83-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 .6 1h1v4h-1a1.65 1.65 0 0 0-.6 1z" />
        </svg>
      </Button>
    </Tooltip>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.profile-switcher {
  display: grid;
  grid-template-columns: 16px minmax(0, 1fr) 28px;
  align-items: center;
  gap: 7px;
  margin: 0 0 8px;
  padding: 8px;
  border-bottom: 1px solid $border-color;
  color: $text-muted;

  &.other {
    color: $warning;
  }
}

.profile-icon {
  flex-shrink: 0;
}

.profile-select {
  min-width: 0;
}

.profile-manage {
  width: 28px;
  min-width: 28px;
  height: 28px;
  padding: 0;
  color: inherit;
}
</style>
