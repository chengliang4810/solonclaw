<script setup lang="ts">
import { computed, reactive } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { useAppStore } from "@/stores/solonclaw/app";
import ThemeSwitch from "./ThemeSwitch.vue";
import SystemNavItems from "./SystemNavItems.vue";

import { clearApiKey } from "@/api/client";
import { getPersonaMeta } from "@/shared/personaMeta";
import { MONITORING_NAV_ITEMS, PERSONA_NAV_ITEMS, PRIMARY_NAV_ITEMS } from "@/shared/sidebarNav";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const appStore = useAppStore();
const selectedKey = computed(() => route.name as string);
const logoPath = '/logo.png';
const personaItems = computed(() => PERSONA_NAV_ITEMS.map(item => ({
  ...item,
  title: getPersonaMeta(item.metaKey).title,
})));

const collapsedGroups = reactive<Record<string, boolean>>({});

function toggleGroup(key: string) {
  collapsedGroups[key] = !collapsedGroups[key];
}

function isGroupCollapsed(key: string) {
  return !!collapsedGroups[key];
}

function handleNav(key: string) {
  router.push({ name: key });
}

function handlePersonaNav(key: string) {
  if (key === 'journal') {
    router.push({ name: 'solonclaw.persona.journal' });
    return;
  }
  router.push({ name: 'solonclaw.persona.file', params: { key } });
}

function handleLogout() {
  clearApiKey();
  router.replace({ name: 'login' });
}
</script>

<template>
  <aside class="sidebar" :class="{ open: appStore.sidebarOpen }">
    <div class="sidebar-logo" @click="router.push('/solonclaw/chat')">
      <img :src="logoPath" alt="solonclaw" class="logo-img" />
      <span class="logo-text">solonclaw</span>
    </div>

    <nav class="sidebar-nav">
      <button
        v-for="item in PRIMARY_NAV_ITEMS"
        :key="item.key"
        class="nav-item"
        :class="{ active: selectedKey === item.key }"
        @click="handleNav(item.key)"
      >
        <span class="nav-item-icon" v-html="item.icon"></span>
        <span>{{ t(item.labelKey) }}</span>
      </button>

      <!-- Persona -->
      <div class="nav-group">
        <div class="nav-group-label" @click="toggleGroup('persona')">
          <span>{{ t("sidebar.groupPersona") }}</span>
          <svg class="nav-group-arrow" :class="{ collapsed: isGroupCollapsed('persona') }" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </div>
        <div v-show="!isGroupCollapsed('persona')">
          <button
            v-for="item in personaItems"
            :key="item.key"
            class="nav-item"
            :class="{ active: (item.key === 'journal' && selectedKey === 'solonclaw.persona.journal') || (selectedKey === 'solonclaw.persona.file' && route.params.key === item.key) }"
            @click="handlePersonaNav(item.key)"
          >
            <span class="nav-item-icon" v-html="item.icon"></span>
            <span>{{ item.title }}</span>
          </button>
        </div>
      </div>

      <!-- Monitoring -->
      <div class="nav-group">
        <div class="nav-group-label" @click="toggleGroup('monitoring')">
          <span>{{ t("sidebar.groupMonitoring") }}</span>
          <svg class="nav-group-arrow" :class="{ collapsed: isGroupCollapsed('monitoring') }" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </div>
        <div v-show="!isGroupCollapsed('monitoring')">
          <button
            v-for="item in MONITORING_NAV_ITEMS"
            :key="item.key"
            class="nav-item"
            :class="{ active: selectedKey === item.key }"
            @click="handleNav(item.key)"
          >
            <span class="nav-item-icon" v-html="item.icon"></span>
            <span>{{ t(item.labelKey) }}</span>
          </button>
        </div>
      </div>

      <!-- System -->
      <div class="nav-group">
        <div class="nav-group-label" @click="toggleGroup('system')">
          <span>{{ t("sidebar.groupSystem") }}</span>
          <svg class="nav-group-arrow" :class="{ collapsed: isGroupCollapsed('system') }" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </div>
        <div v-show="!isGroupCollapsed('system')">
          <SystemNavItems :selected-key="selectedKey" @navigate="handleNav" />
        </div>
      </div>
    </nav>

    <div class="sidebar-footer">
      <button class="nav-item logout-item" @click="handleLogout">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
          <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
          <polyline points="16 17 21 12 16 7" />
          <line x1="21" y1="12" x2="9" y2="12" />
        </svg>
        <span>{{ t("sidebar.logout") }}</span>
      </button>
      <div class="status-row">
        <div
          class="status-indicator"
          :class="{
            connected: appStore.connected,
            disconnected: !appStore.connected,
          }"
        >
          <span class="status-dot"></span>
          <span class="status-text">{{
            appStore.connected
              ? t("sidebar.connected")
              : t("sidebar.disconnected")
          }}</span>
        </div>
        <ThemeSwitch />
      </div>
    </div>
  </aside>
</template>

<style scoped lang="scss" src="./AppSidebar.scss"></style>
