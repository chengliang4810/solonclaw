<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Button, Drawer, Input, Spin, Tag, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/solonclaw/settings'
import { downloadMedia, fetchMedia, fetchMediaDetail, indexMedia, referenceMedia, refreshMedia, type ChannelMedia } from '@/api/solonclaw/media'
import PlatformSettings from '@/components/solonclaw/settings/PlatformSettings.vue'

const settingsStore = useSettingsStore()
const { t } = useI18n()
const mediaItems = ref<ChannelMedia[]>([])
const mediaLoading = ref(false)
const mediaRefreshLoading = ref(false)
const mediaDownloadLoading = ref(false)
const mediaIndexLoading = ref(false)
const mediaReferenceLoading = ref(false)
const mediaIndexPath = ref('')
const selectedMediaDetail = ref<ChannelMedia | null>(null)
const mediaDetailOpen = ref(false)

onMounted(() => {
  settingsStore.fetchSettings()
  loadMedia()
})

async function loadMedia() {
  mediaLoading.value = true
  try {
    mediaItems.value = await fetchMedia('', 30)
  } finally {
    mediaLoading.value = false
  }
}

async function openMediaDetail(mediaId: string) {
  selectedMediaDetail.value = await fetchMediaDetail(mediaId)
  mediaDetailOpen.value = true
}

async function refreshSelectedMedia() {
  const mediaId = selectedMediaDetail.value?.media_id
  if (!mediaId) return
  mediaRefreshLoading.value = true
  try {
    selectedMediaDetail.value = await refreshMedia(mediaId)
    mediaItems.value = await fetchMedia('', 30)
  } finally {
    mediaRefreshLoading.value = false
  }
}

async function downloadSelectedMedia() {
  const mediaId = selectedMediaDetail.value?.media_id
  if (!mediaId) return
  mediaDownloadLoading.value = true
  try {
    selectedMediaDetail.value = await downloadMedia(mediaId)
    mediaItems.value = await fetchMedia('', 30)
    message.success(t('channels.mediaDownloadReady'))
  } catch (err: any) {
    message.error(err.message || t('channels.mediaDownloadFailed'))
  } finally {
    mediaDownloadLoading.value = false
  }
}

async function handleIndexMedia() {
  const localPath = mediaIndexPath.value.trim()
  if (!localPath) return
  mediaIndexLoading.value = true
  try {
    await indexMedia({ localPath })
    mediaIndexPath.value = ''
    await loadMedia()
    message.success(t('channels.mediaIndexReady'))
  } catch (err: any) {
    message.error(err.message || t('channels.mediaIndexFailed'))
  } finally {
    mediaIndexLoading.value = false
  }
}

async function referenceSelectedMedia() {
  const mediaId = selectedMediaDetail.value?.media_id
  if (!mediaId) return
  mediaReferenceLoading.value = true
  try {
    selectedMediaDetail.value = await referenceMedia(mediaId)
    mediaItems.value = await fetchMedia('', 30)
    message.success(t('channels.mediaReferenceReady'))
  } catch (err: any) {
    message.error(err.message || t('channels.mediaReferenceFailed'))
  } finally {
    mediaReferenceLoading.value = false
  }
}

function formatBytes(value?: number) {
  if (!value) return '-'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function formatTime(value?: number) {
  return value ? new Date(value).toLocaleString() : '-'
}
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
      <Spin :spinning="settingsStore.loading || settingsStore.saving" size="large" :description="t('common.loading')">
        <PlatformSettings v-if="!settingsStore.loading" />
      </Spin>

      <section class="media-cache">
        <div class="section-head">
          <div>
            <h3>{{ t('channels.mediaCache') }}</h3>
            <p>{{ t('channels.mediaDescription') }}</p>
          </div>
          <Button size="small" :loading="mediaLoading" @click="loadMedia">{{ t('channels.mediaRefresh') }}</Button>
        </div>
        <div class="media-index-row">
          <Input
            v-model:value="mediaIndexPath"
            size="small"
            :placeholder="t('channels.mediaIndexPlaceholder')"
            @press-enter="handleIndexMedia"
          />
          <Button size="small" type="primary" :loading="mediaIndexLoading" :disabled="!mediaIndexPath.trim()" @click="handleIndexMedia">
            {{ t('channels.mediaIndexLocal') }}
          </Button>
        </div>
        <Spin :spinning="mediaLoading" size="small">
          <div v-if="mediaItems.length === 0" class="empty-state">{{ t('channels.mediaEmpty') }}</div>
          <div v-else class="media-list">
            <button v-for="item in mediaItems" :key="item.media_id" class="media-row" @click="openMediaDetail(item.media_id)">
              <span class="media-name">{{ item.original_name || item.media_id }}</span>
              <span class="media-meta">{{ item.platform || '-' }} / {{ item.kind || '-' }} / {{ formatBytes(item.size_bytes) }}</span>
              <Tag size="small" :bordered="false">{{ item.status || '-' }}</Tag>
            </button>
          </div>
        </Spin>
      </section>
    </div>

    <Drawer v-model:open="mediaDetailOpen" placement="right" :style="{ width: '520px' }" :title="t('channels.mediaDetail')">
      <div v-if="selectedMediaDetail" class="media-detail">
        <div class="media-actions">
          <Button size="small" :loading="mediaRefreshLoading" @click="refreshSelectedMedia">
            {{ t('channels.mediaRefreshDetail') }}
          </Button>
          <Button size="small" :loading="mediaDownloadLoading" @click="downloadSelectedMedia">
            {{ t('channels.mediaDownload') }}
          </Button>
          <Button size="small" :loading="mediaReferenceLoading" @click="referenceSelectedMedia">
            {{ t('channels.mediaMakeReference') }}
          </Button>
        </div>
        <div><span>{{ t('channels.mediaStatus') }}</span><strong>{{ selectedMediaDetail.status || '-' }}</strong></div>
        <div><span>ID</span><strong>{{ selectedMediaDetail.media_id }}</strong></div>
        <div><span>{{ t('channels.mediaPlatform') }}</span><strong>{{ selectedMediaDetail.platform || '-' }}</strong></div>
        <div><span>{{ t('channels.mediaKind') }}</span><strong>{{ selectedMediaDetail.kind || '-' }}</strong></div>
        <div><span>{{ t('channels.mediaSize') }}</span><strong>{{ formatBytes(selectedMediaDetail.size_bytes) }}</strong></div>
        <div><span>{{ t('channels.mediaUpdated') }}</span><strong>{{ formatTime(selectedMediaDetail.updated_at) }}</strong></div>
        <div><span>{{ t('channels.mediaPath') }}</span><strong>{{ selectedMediaDetail.local_path || '-' }}</strong></div>
        <div><span>{{ t('channels.mediaReference') }}</span><strong>{{ selectedMediaDetail.reference || '-' }}</strong></div>
        <div v-if="selectedMediaDetail.error"><span>{{ t('channels.mediaError') }}</span><strong>{{ selectedMediaDetail.error }}</strong></div>
      </div>
    </Drawer>
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

.media-cache {
  margin-top: 20px;
  padding: 16px;
  background: $bg-card;
  border: 1px solid $border-color;
  border-radius: $radius-md;
}

.section-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;

  h3 {
    margin: 0 0 4px;
    font-size: 15px;
    color: $text-primary;
  }

  p {
    margin: 0;
    font-size: 12px;
    color: $text-muted;
  }
}

.media-index-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  margin-bottom: 12px;
}

.empty-state {
  padding: 24px 0;
  text-align: center;
  color: $text-muted;
}

.media-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.media-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(180px, auto) auto;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  background: $bg-input;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.media-name,
.media-meta {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.media-name {
  font-size: 13px;
  color: $text-primary;
}

.media-meta {
  font-size: 12px;
  color: $text-muted;
}

.media-detail {
  display: flex;
  flex-direction: column;
  gap: 10px;

  div {
    display: grid;
    grid-template-columns: 120px minmax(0, 1fr);
    gap: 12px;
  }

  .media-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  span {
    color: $text-muted;
  }

  strong {
    overflow-wrap: anywhere;
    font-weight: 500;
    color: $text-primary;
  }
}
</style>
