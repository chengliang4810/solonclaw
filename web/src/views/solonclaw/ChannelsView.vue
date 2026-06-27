<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Button, Select, Spin, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/solonclaw/settings'
import { formatFileSize } from '@/shared/file-size'
import PlatformSettings from '@/components/solonclaw/settings/PlatformSettings.vue'
import {
  downloadMedia,
  fetchMedia,
  referenceMedia,
  refreshMedia,
  type ChannelMedia,
} from '@/api/solonclaw/media'

const settingsStore = useSettingsStore()
const { t } = useI18n()
const mediaItems = ref<ChannelMedia[]>([])
const mediaLoading = ref(false)
const mediaActionId = ref('')
const mediaPlatform = ref('')

const mediaPlatformOptions = computed(() => [
  { label: t('channels.mediaAllPlatforms'), value: '' },
  ...Array.from(new Set(mediaItems.value.map(item => item.platform).filter(Boolean))).map(platform => ({
    label: platform,
    value: platform,
  })),
])

async function loadMedia() {
  mediaLoading.value = true
  try {
    mediaItems.value = await fetchMedia(mediaPlatform.value, 50)
  } catch (err: any) {
    message.error(`${t('channels.mediaLoadFailed')}: ${err.message}`)
  } finally {
    mediaLoading.value = false
  }
}

async function handleMediaAction(mediaId: string, action: 'refresh' | 'download' | 'reference') {
  mediaActionId.value = `${action}:${mediaId}`
  try {
    const result = action === 'refresh'
      ? await refreshMedia(mediaId)
      : action === 'download'
        ? await downloadMedia(mediaId)
        : await referenceMedia(mediaId)
    if (action === 'reference') {
      const reference = String((result as any)?.reference || '')
      if (reference) {
        await navigator.clipboard?.writeText(reference)
        message.success(t('channels.mediaReferenceCopied'))
      }
    } else {
      message.success(t(action === 'refresh' ? 'channels.mediaRefreshQueued' : 'channels.mediaDownloadReady'))
    }
    await loadMedia()
  } catch (err: any) {
    message.error(`${t('channels.mediaActionFailed')}: ${err.message}`)
  } finally {
    mediaActionId.value = ''
  }
}

function formatMediaTime(value?: number) {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}

onMounted(() => {
  settingsStore.fetchSettings()
  loadMedia()
})
</script>

<template>
  <div class="channels-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('sidebar.channels') }}</h2>
        <p class="header-subtitle">{{ t('channels.description') }}</p>
      </div>
    </header>

    <div class="channels-content">
      <Spin :spinning="settingsStore.loading || settingsStore.saving" size="large" :tip="t('common.loading')">
        <PlatformSettings v-if="!settingsStore.loading" />
      </Spin>

      <section class="media-panel">
        <div class="media-header">
          <div>
            <h3>{{ t('channels.mediaTitle') }}</h3>
            <p>{{ t('channels.mediaDescription') }}</p>
          </div>
          <div class="media-actions">
            <Select
              v-model:value="mediaPlatform"
              :options="mediaPlatformOptions"
              size="small"
              class="platform-select"
              @update:value="loadMedia"
            />
            <Button size="small" :loading="mediaLoading" @click="loadMedia">{{ t('common.refresh') }}</Button>
          </div>
        </div>

        <Spin :spinning="mediaLoading">
          <div v-if="mediaItems.length" class="media-list">
            <article v-for="item in mediaItems" :key="item.media_id" class="media-item">
              <div class="media-main">
                <div class="media-title">
                  <strong>{{ item.original_name || item.media_id }}</strong>
                  <span>{{ item.platform || '-' }}</span>
                  <span>{{ item.status || '-' }}</span>
                </div>
                <div class="media-meta">
                  <span>{{ item.kind || item.mime_type || '-' }}</span>
                  <span>{{ formatFileSize(item.size_bytes) }}</span>
                  <span>{{ formatMediaTime(item.updated_at) }}</span>
                </div>
                <code>{{ item.reference || item.local_path || '-' }}</code>
                <p v-if="item.error" class="media-error">{{ item.error }}</p>
              </div>
              <div class="media-item-actions">
                <Button
                  size="small"
                  :loading="mediaActionId === `refresh:${item.media_id}`"
                  @click="handleMediaAction(item.media_id, 'refresh')"
                >
                  {{ t('channels.mediaRefresh') }}
                </Button>
                <Button
                  size="small"
                  :loading="mediaActionId === `download:${item.media_id}`"
                  @click="handleMediaAction(item.media_id, 'download')"
                >
                  {{ t('channels.mediaDownload') }}
                </Button>
                <Button
                  size="small"
                  type="primary"
                  :loading="mediaActionId === `reference:${item.media_id}`"
                  @click="handleMediaAction(item.media_id, 'reference')"
                >
                  {{ t('channels.mediaCopyReference') }}
                </Button>
              </div>
            </article>
          </div>
          <div v-else class="media-empty">{{ t('channels.mediaEmpty') }}</div>
        </Spin>
      </section>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.channels-view {
  height: calc(100 * var(--vh));
  display: flex;
  flex-direction: column;
}

.channels-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  position: relative;
}

.media-panel {
  margin-top: 20px;
  border-top: 1px solid $border-color;
  padding-top: 20px;
}

.media-header,
.media-item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}

.media-header {
  margin-bottom: 12px;

  h3 {
    margin: 0;
    font-size: 15px;
    color: $text-primary;
  }

  p {
    margin: 4px 0 0;
    color: $text-muted;
    font-size: 12px;
  }
}

.media-actions,
.media-item-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.platform-select {
  width: 150px;
}

.media-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.media-item {
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  background: $bg-card;
  padding: 10px 12px;
}

.media-main {
  min-width: 0;
  flex: 1;

  code {
    display: block;
    margin-top: 6px;
    color: $text-muted;
    font-size: 11px;
    word-break: break-all;
  }
}

.media-title,
.media-meta {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.media-title {
  color: $text-primary;
  font-size: 13px;

  span {
    color: $text-muted;
    font-size: 12px;
  }
}

.media-meta,
.media-empty {
  color: $text-muted;
  font-size: 12px;
}

.media-error {
  margin: 6px 0 0;
  color: $error;
  font-size: 12px;
}

@media (max-width: $breakpoint-mobile) {
  .media-header,
  .media-item {
    flex-direction: column;
  }

  .media-actions,
  .media-item-actions {
    width: 100%;
    justify-content: flex-end;
    flex-wrap: wrap;
  }
}
</style>
