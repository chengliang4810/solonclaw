<script setup lang="ts">
import { ref, computed, nextTick, watch } from 'vue'
import { NModal, NInput } from 'naive-ui'
import { useAppStore } from '@/stores/solonclaw/app'
import { useI18n } from 'vue-i18n'
import { nextPanelCursor, normalizePanelKey } from '@/shared/panelNavigation'
import { visibleModelPickerItems } from '@/shared/modelPicker'

const { t } = useI18n()
const appStore = useAppStore()

const showModal = ref(false)
const searchQuery = ref('')
const collapsedGroups = ref<Record<string, boolean>>({})
const cursor = ref(0)
const listRef = ref<HTMLElement | null>(null)

const filteredGroups = computed(() => {
  const q = searchQuery.value.toLowerCase().trim()
  if (!q) return appStore.modelGroups
  return appStore.modelGroups
    .map(g => ({
      ...g,
      models: g.models.filter(m => m.toLowerCase().includes(q)),
    }))
    .filter(g => g.models.length > 0 || g.label.toLowerCase().includes(q))
})

const selectableItems = computed(() => visibleModelPickerItems(filteredGroups.value, collapsedGroups.value))

function toggleGroup(provider: string) {
  collapsedGroups.value[provider] = !collapsedGroups.value[provider]
  cursor.value = nextPanelCursor(cursor.value, 'none', selectableItems.value.length)
}

function isGroupCollapsed(provider: string) {
  return !!collapsedGroups.value[provider]
}

function handleSelect(model: string, provider: string) {
  appStore.switchModel(model, provider)
  showModal.value = false
  searchQuery.value = ''
  cursor.value = 0
}

function openModal() {
  collapsedGroups.value = {}
  searchQuery.value = ''
  cursor.value = 0
  showModal.value = true
  nextTick(() => listRef.value?.focus())
}

function handleListKey(event: KeyboardEvent) {
  const action = normalizePanelKey(event.key)
  if (action === 'none') return
  event.preventDefault()
  if (action === 'cancel') {
    showModal.value = false
    return
  }
  if (action === 'select') {
    const item = selectableItems.value[cursor.value]
    if (item) handleSelect(item.model, item.provider)
    return
  }
  cursor.value = nextPanelCursor(cursor.value, action, selectableItems.value.length)
}

function modelItemClass(model: string, provider: string) {
  const key = `${provider}:${model}`
  const active = model === appStore.selectedModel && provider === appStore.selectedProvider
  const cursorActive = selectableItems.value[cursor.value]?.key === key
  return { active, cursor: cursorActive }
}

watch(searchQuery, () => {
  cursor.value = 0
})

watch(selectableItems, (items) => {
  cursor.value = nextPanelCursor(cursor.value, 'none', items.length)
})
</script>

<template>
  <div class="model-selector">
    <div class="model-label">{{ t('models.title') }}</div>
    <button class="model-trigger" @click="openModal">
      <span class="model-name" :title="appStore.selectedModel">{{ appStore.selectedModel || '—' }}</span>
      <svg class="model-arrow" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <polyline points="6 9 12 15 18 9" />
      </svg>
    </button>

    <NModal
      v-model:show="showModal"
      preset="card"
      :title="t('models.title')"
      :style="{ width: 'min(480px, calc(100vw - 32px))' }"
      :mask-closable="true"
    >
      <NInput
        v-model:value="searchQuery"
        :placeholder="t('models.searchPlaceholder')"
        clearable
        size="small"
        class="model-search"
      />
      <div class="model-list" ref="listRef" tabindex="0" @keydown="handleListKey">
        <div v-for="group in filteredGroups" :key="group.provider" class="model-group">
          <div class="model-group-header" @click="toggleGroup(group.provider)">
            <svg
              class="model-group-arrow"
              :class="{ collapsed: isGroupCollapsed(group.provider) }"
              width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
            >
              <polyline points="6 9 12 15 18 9" />
            </svg>
            <span class="model-group-label">{{ group.label }}</span>
            <span class="model-group-count">{{ group.models.length }}</span>
          </div>
          <div v-show="!isGroupCollapsed(group.provider)" class="model-group-items">
            <div
              v-for="model in group.models"
              :key="model"
              class="model-item"
              :class="modelItemClass(model, group.provider)"
              @click="handleSelect(model, group.provider)"
            >
              <span class="model-item-name">{{ model }}</span>
              <svg v-if="model === appStore.selectedModel && group.provider === appStore.selectedProvider" class="model-check" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="20 6 9 17 4 12" />
              </svg>
            </div>
          </div>
        </div>
        <div v-if="filteredGroups.length === 0" class="model-empty">
          {{ searchQuery ? 'No results' : 'No models' }}
        </div>
      </div>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.model-selector {
  padding: 0 12px;
  margin-bottom: 8px;
}

.model-label {
  font-size: 11px;
  font-weight: 600;
  color: $text-muted;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 6px;
}

.model-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 6px 8px;
  background: $bg-input;
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  color: $text-primary;
  font-size: 13px;
  cursor: pointer;
  transition: border-color $transition-fast;

  &:hover {
    border-color: $accent-muted;
  }
}

.model-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
}

.model-arrow {
  flex-shrink: 0;
  color: $text-muted;
}

.model-search {
  margin-bottom: 12px;
}

.model-list {
  max-height: 50vh;
  overflow-y: auto;
  scrollbar-width: thin;
}

.model-group {
  margin-bottom: 4px;
}

.model-group-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 8px;
  font-size: 12px;
  font-weight: 600;
  color: $text-secondary;
  cursor: pointer;
  border-radius: $radius-sm;
  user-select: none;
  transition: background-color $transition-fast;

  &:hover {
    background-color: $bg-secondary;
  }
}

.model-group-arrow {
  flex-shrink: 0;
  transition: transform $transition-fast;

  &.collapsed {
    transform: rotate(-90deg);
  }
}

.model-group-label {
  flex: 1;
}

.model-group-count {
  font-size: 11px;
  color: $text-muted;
  font-weight: 400;
}

.model-group-items {
  padding-left: 8px;
}

.model-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  font-size: 13px;
  color: $text-secondary;
  border-radius: $radius-sm;
  cursor: pointer;
  transition: all $transition-fast;

  &:hover {
    background-color: rgba(var(--accent-primary-rgb), 0.06);
    color: $text-primary;
  }

  &.active {
    color: $accent-primary;
    font-weight: 500;
  }

  &.cursor {
    outline: 2px solid $accent-primary;
    outline-offset: 2px;
  }
}

.model-item-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: $font-code;
  font-size: 12px;
}

.model-check {
  flex-shrink: 0;
  color: $accent-primary;
}

.model-empty {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
  color: $text-muted;
}
</style>
