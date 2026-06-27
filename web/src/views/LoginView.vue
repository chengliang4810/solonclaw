<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { clearApiKey, dashboardFetch, getApiKey, getBaseUrlValue, handleDashboardAuthFailure, setApiKey, hasApiKey } from "@/api/client";

const { t } = useI18n();
const router = useRouter();

// Read token saved by main.ts (before router strips URL params)
const urlToken = (window as any).__LOGIN_TOKEN__ || "";

const token = ref(urlToken);
const loading = ref(false);
const errorMsg = ref("");

async function validateExistingToken() {
  const existingKey = (urlToken || getApiKey()).trim();
  if (!existingKey) {
    return;
  }

  loading.value = true;
  errorMsg.value = "";
  try {
    const res = await dashboardFetch(`${getBaseUrlValue()}/api/sessions`, {
      headers: { Authorization: `Bearer ${existingKey}` },
    });

    if (res.ok) {
      router.replace("/solonclaw/chat");
      return;
    }

    const body = await res.text().catch(() => "");
    handleDashboardAuthFailure(res.status, body);
    clearApiKey();
    token.value = "";
  } catch {
    clearApiKey();
    token.value = "";
  } finally {
    loading.value = false;
  }
}

onMounted(async () => {
  if (hasApiKey()) {
    await validateExistingToken();
  }
});

async function handleLogin() {
  const key = token.value.trim();
  if (!key) {
    errorMsg.value = t("login.tokenRequired");
    return;
  }

  loading.value = true;
  errorMsg.value = "";

  try {
    const res = await dashboardFetch(`${getBaseUrlValue()}/api/sessions`, {
      headers: { Authorization: `Bearer ${key}` },
    });

    if (!res.ok) {
      const body = await res.text().catch(() => "");
      errorMsg.value = t("login.invalidToken");
      if (res.status !== 401 && handleDashboardAuthFailure(res.status, body)) {
        errorMsg.value = t("login.connectionFailed");
      }
      loading.value = false;
      return;
    }

    setApiKey(key);
    router.replace("/solonclaw/chat");
  } catch {
    errorMsg.value = t("login.connectionFailed");
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="login-view">
    <div class="login-card">
      <div class="login-logo">
        <img src="/logo.png" alt="solonclaw" width="80" height="80" />
      </div>
      <h1 class="login-title">{{ t("login.title") }}</h1>
      <p class="login-desc">{{ t("login.description") }}</p>

      <form class="login-form" @submit.prevent="handleLogin">
        <input
          v-model="token"
          type="password"
          class="login-input"
          :placeholder="t('login.placeholder')"
          autofocus
        />

        <div v-if="errorMsg" class="login-error">{{ errorMsg }}</div>
        <button type="submit" class="login-btn" :disabled="loading">
          {{ loading ? "..." : t("login.submit") }}
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use "@/styles/variables" as *;

.login-view {
  height: calc(100 * var(--vh));
  display: flex;
  align-items: center;
  justify-content: center;
  background: $bg-primary;
}

.login-card {
  width: 480px;
  max-width: calc(100vw - 32px);
  padding: 56px;
  border: 1px solid $border-color;
  border-radius: $radius-lg;
  background: $bg-card;
  text-align: center;

  @media (max-width: $breakpoint-mobile) {
    padding: 32px 24px;
  }
}

.login-logo {
  margin-bottom: 24px;
}

.login-title {
  font-size: 26px;
  font-weight: 600;
  color: $text-primary;
  margin: 0 0 10px;
}

.login-desc {
  font-size: 14px;
  color: $text-muted;
  margin: 0 0 32px;
  line-height: 1.6;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.login-input {
  width: 100%;
  padding: 14px 16px;
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  font-size: 15px;
  color: $text-primary;
  background: $bg-input;
  outline: none;
  transition: border-color $transition-fast;
  box-sizing: border-box;
  font-family: $font-code;

  &::placeholder {
    color: $text-muted;
  }

  &:focus {
    border-color: $accent-primary;
  }
}

.login-error {
  font-size: 13px;
  color: $error;
  text-align: left;
}

.login-btn {
  width: 100%;
  padding: 14px;
  border: none;
  border-radius: $radius-sm;
  background: $text-primary;
  color: var(--text-on-accent);
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity $transition-fast;

  &:hover {
    opacity: 0.85;
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}
</style>
