<script setup lang="ts">
import { renameSession } from '@/api/solonclaw/sessions'
import { useChatStore, type Session } from '@/stores/solonclaw/chat'
import { useSessionBrowserPrefsStore } from '@/stores/solonclaw/session-browser-prefs'
import { Button, Dropdown, Input, Modal, Tooltip, message } from 'antdv-next'
import type { InputRef, MenuProps } from 'antdv-next'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getSourceLabel } from '@/shared/session-display'
import { copyToClipboard } from '@/utils/clipboard'
import AgentSelector from '@/components/layout/AgentSelector.vue'
import ChatInput from './ChatInput.vue'
import ConversationMonitorPane from './ConversationMonitorPane.vue'
import MessageList from './MessageList.vue'
import SessionListItem from './SessionListItem.vue'

const chatStore = useChatStore()
const sessionBrowserPrefsStore = useSessionBrowserPrefsStore()
const { t } = useI18n()

const currentMode = ref<'chat' | 'live'>('chat')

// Initialize synchronously from the media query so first paint is correct.
// On narrow viewports the session list is an absolute-positioned overlay
// (z-index 10) on top of the chat area; if we default to `true`, onMounted
// only flips it to `false` AFTER the first render, causing a visible flash
// where the session list covers the chat content ("auto-fixes after a
// moment" — that was the race).
const showSessions = ref(
  typeof window === 'undefined' || !window.matchMedia('(max-width: 768px)').matches,
)
const lastChatSessionsVisibility = ref(showSessions.value)
let mobileQuery: MediaQueryList | null = null
let passiveRefreshTimer: ReturnType<typeof setInterval> | null = null
const isMobile = ref(false)
const PASSIVE_REFRESH_INTERVAL_MS = 15000
const COLLAPSED_GROUPS_KEY = 'solonclaw_collapsed_groups'

// 会话分组折叠状态只影响当前浏览器；缓存损坏时回退默认值，避免聊天页启动失败。
function loadCollapsedGroupSources(): string[] {
  try {
    const raw = localStorage.getItem(COLLAPSED_GROUPS_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((source): source is string => typeof source === 'string') : []
  } catch {
    return []
  }
}

function hasStoredCollapsedGroups(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_GROUPS_KEY) !== null
  } catch {
    return false
  }
}

function saveCollapsedGroupSources(groups: Set<string>) {
  try {
    localStorage.setItem(COLLAPSED_GROUPS_KEY, JSON.stringify([...groups]))
  } catch {
    // 本地偏好保存失败时只影响当前浏览器，不影响对话主流程。
  }
}

function handleSessionClick(sessionId: string) {
  chatStore.switchSession(sessionId)
  if (mobileQuery?.matches) showSessions.value = false
}

function handleModeChange(mode: 'chat' | 'live') {
  if (mode === currentMode.value) return
  if (mode === 'live') {
    lastChatSessionsVisibility.value = showSessions.value
    showSessions.value = false
  } else {
    showSessions.value = mobileQuery?.matches ? false : lastChatSessionsVisibility.value
  }
  currentMode.value = mode
}

function handleMobileChange(e: MediaQueryListEvent | MediaQueryList) {
  isMobile.value = e.matches
  if (e.matches && showSessions.value) {
    showSessions.value = false
  }
}

async function refreshVisibleActiveSession() {
  if (currentMode.value !== 'chat' || chatStore.isRunActive || document.hidden) return
  await chatStore.refreshActiveSession()
}

onMounted(() => {
  mobileQuery = window.matchMedia('(max-width: 768px)')
  handleMobileChange(mobileQuery)
  mobileQuery.addEventListener('change', handleMobileChange)
  passiveRefreshTimer = setInterval(refreshVisibleActiveSession, PASSIVE_REFRESH_INTERVAL_MS)
  document.addEventListener('visibilitychange', refreshVisibleActiveSession)
})

onUnmounted(() => {
  mobileQuery?.removeEventListener('change', handleMobileChange)
  if (passiveRefreshTimer) {
    clearInterval(passiveRefreshTimer)
    passiveRefreshTimer = null
  }
  document.removeEventListener('visibilitychange', refreshVisibleActiveSession)
})
const showRenameModal = ref(false)
const renameValue = ref('')
const renameSessionId = ref<string | null>(null)
const renameInputRef = ref<InputRef | null>(null)
const collapsedGroups = ref<Set<string>>(new Set(loadCollapsedGroupSources()))

// Source sort order: api_server first, cron last, others alphabetical
function sourceSortKey(source: string): number {
  if (source === 'api_server') return -1
  if (source === 'cron') return 999
  return 0
}

function sortSessionsWithActiveFirst(items: Session[]): Session[] {
  return [...items].sort((a, b) => {
    const aLive = chatStore.isSessionLive(a.id)
    const bLive = chatStore.isSessionLive(b.id)
    if (aLive !== bLive) return aLive ? -1 : 1
    return (b.updatedAt || 0) - (a.updatedAt || 0)
  })
}

// Group sessions by source, with sort order
interface SessionGroup {
  source: string
  label: string
  sessions: Session[]
}

const pinnedSessions = computed(() =>
  sortSessionsWithActiveFirst(chatStore.sessions.filter(session => sessionBrowserPrefsStore.isPinned(session.id))),
)

const groupedSessions = computed<SessionGroup[]>(() => {
  const map = new Map<string, Session[]>()
  for (const s of chatStore.sessions) {
    if (sessionBrowserPrefsStore.isPinned(s.id)) continue
    const key = s.source || ''
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(s)
  }

  const keys = [...map.keys()].sort((a, b) => {
    const aHasLive = map.get(a)?.some(s => chatStore.isSessionLive(s.id)) || false
    const bHasLive = map.get(b)?.some(s => chatStore.isSessionLive(s.id)) || false
    if (aHasLive !== bHasLive) return aHasLive ? -1 : 1
    const ka = sourceSortKey(a)
    const kb = sourceSortKey(b)
    if (ka !== kb) return ka - kb
    return a.localeCompare(b)
  })

  return keys.map(key => ({
    source: key,
    label: key ? getSourceLabel(key) : t('chat.other'),
    sessions: sortSessionsWithActiveFirst(map.get(key)!),
  }))
})

function toggleGroup(source: string) {
  const isExpanded = !collapsedGroups.value.has(source)
  if (isExpanded) {
    collapsedGroups.value = new Set([...collapsedGroups.value, source])
  } else {
    collapsedGroups.value = new Set(
      groupedSessions.value.map(g => g.source).filter(s => s !== source),
    )
    const group = groupedSessions.value.find(g => g.source === source)
    if (group?.sessions.length) {
      chatStore.switchSession(group.sessions[0].id)
    }
  }
  saveCollapsedGroupSources(collapsedGroups.value)
}

watch(groupedSessions, groups => {
  if (hasStoredCollapsedGroups()) {
    const activeSource = chatStore.activeSession?.source
    if (activeSource && collapsedGroups.value.has(activeSource)) {
      collapsedGroups.value = new Set([...collapsedGroups.value].filter(source => source !== activeSource))
      saveCollapsedGroupSources(collapsedGroups.value)
    }
    return
  }
  collapsedGroups.value = new Set(groups.slice(1).map(group => group.source))
  saveCollapsedGroupSources(collapsedGroups.value)
}, { once: true })

watch(
  () => [chatStore.sessionsLoaded, ...chatStore.sessions.map(session => session.id)],
  value => {
    const sessionIds = value.slice(1) as string[]
    if (!value[0] || sessionIds.length === 0) return
    sessionBrowserPrefsStore.pruneMissingSessions(sessionIds)
  },
  { immediate: true },
)

const activeSessionTitle = computed(() =>
  chatStore.activeSession?.title || t('chat.newChat'),
)

const headerTitle = computed(() =>
  currentMode.value === 'live' ? t('chat.liveSessions') : activeSessionTitle.value,
)

const activeSessionSource = computed(() =>
  currentMode.value === 'chat' ? (chatStore.activeSession?.source || '') : '',
)

const activeGoalState = computed(() =>
  currentMode.value === 'chat' ? chatStore.activeSession?.goalState || null : null,
)

const activeGoalLabel = computed(() => {
  const goal = activeGoalState.value
  if (!goal) return ''
  const statusMap: Record<string, string> = {
    active: t('chat.goalStatusActive'),
    paused: t('chat.goalStatusPaused'),
    done: t('chat.goalStatusDone'),
  }
  const status = statusMap[goal.status] || t('chat.goalStatusUnknown', { status: goal.status })
  return t('chat.goalProgress', {
    status,
    used: goal.turns_used,
    max: goal.max_turns,
  })
})

const activeGoalTitle = computed(() => {
  const goal = activeGoalState.value
  if (!goal) return ''
  const parts = [goal.goal]
  if (goal.last_verdict) parts.push(t('chat.goalJudge', { verdict: goal.last_verdict }))
  if (goal.last_reason) parts.push(goal.last_reason)
  if (goal.paused_reason) parts.push(goal.paused_reason)
  return parts.filter(Boolean).join('\n')
})

const canPauseGoal = computed(() =>
  currentMode.value === 'chat' && activeGoalState.value?.status === 'active' && !chatStore.isStreaming,
)

const canResumeGoal = computed(() =>
  currentMode.value === 'chat' && activeGoalState.value?.status === 'paused' && !chatStore.isStreaming,
)

const canClearGoal = computed(() =>
  currentMode.value === 'chat'
    && !!activeGoalState.value
    && activeGoalState.value.status !== 'done'
    && !chatStore.isStreaming,
)

async function runGoalCommand(action: 'pause' | 'resume' | 'clear') {
  const ok = await chatStore.sendSlashCommand(`/goal ${action}`)
  if (!ok) return
  await chatStore.refreshActiveSession()
}

function handleNewChat() {
  chatStore.newChat()
}

async function copySessionId(id?: string) {
  const sessionId = id || chatStore.activeSessionId
  if (sessionId) {
    const ok = await copyToClipboard(sessionId)
    if (ok) message.success(t('common.copied'))
    else message.error(t('common.copied') + ' ✗')
  }
}

function handleDeleteSession(id: string) {
  sessionBrowserPrefsStore.removePinned(id)
  chatStore.deleteSession(id)
  message.success(t('chat.sessionDeleted'))
}

const contextSessionId = ref<string | null>(null)
const contextSessionPinned = computed(() =>
  contextSessionId.value ? sessionBrowserPrefsStore.isPinned(contextSessionId.value) : false,
)

const contextMenuOptions = computed(() => [
  { label: t(contextSessionPinned.value ? 'chat.unpin' : 'chat.pin'), key: 'pin' },
  { label: t('chat.rename'), key: 'rename' },
  { label: t('chat.copySessionId'), key: 'copy-id' },
])

const contextMenuItems = computed<MenuProps['items']>(() => contextMenuOptions.value)

const contextMenuStyle = computed(() => ({
  left: `${contextMenuX.value}px`,
  top: `${contextMenuY.value}px`,
}))

function handleContextMenu(e: MouseEvent, sessionId: string) {
  e.preventDefault()
  contextSessionId.value = sessionId
  showContextMenu.value = true
  contextMenuX.value = e.clientX
  contextMenuY.value = e.clientY
}

const showContextMenu = ref(false)
const contextMenuX = ref(0)
const contextMenuY = ref(0)

function handleContextMenuSelect(key: string) {
  showContextMenu.value = false
  if (!contextSessionId.value) return
  if (key === 'pin') {
    sessionBrowserPrefsStore.togglePinned(contextSessionId.value)
    return
  }
  if (key === 'copy-id') {
    copySessionId(contextSessionId.value)
  } else if (key === 'rename') {
    const session = chatStore.sessions.find(s => s.id === contextSessionId.value)
    renameSessionId.value = contextSessionId.value
    renameValue.value = session?.title || ''
    showRenameModal.value = true
    nextTick(() => {
      renameInputRef.value?.focus()
    })
  }
}

function handleClickOutside() {
  showContextMenu.value = false
}

function handleContextMenuClick(info: { key: string | number }) {
  handleContextMenuSelect(String(info.key))
}

async function handleRenameConfirm() {
  if (!renameSessionId.value || !renameValue.value.trim()) return
  const ok = await renameSession(renameSessionId.value, renameValue.value.trim())
  if (ok) {
    const session = chatStore.sessions.find(s => s.id === renameSessionId.value)
    if (session) session.title = renameValue.value.trim()
    if (chatStore.activeSession?.id === renameSessionId.value) {
      chatStore.activeSession.title = renameValue.value.trim()
    }
    message.success(t('chat.renamed'))
  } else {
    message.error(t('chat.renameFailed'))
  }
  showRenameModal.value = false
}
</script>

<template>
  <div class="chat-panel">
    <div v-if="currentMode === 'chat'" class="session-backdrop" :class="{ active: showSessions }" @click="showSessions = false" />
    <aside v-if="currentMode === 'chat'" class="session-list" :class="{ collapsed: !showSessions }">
      <div class="session-list-header">
        <span v-if="showSessions" class="session-list-title">{{ t('chat.sessions') }}</span>
        <div class="session-list-actions">
          <button class="session-close-btn" @click="showSessions = false">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
          <Button type="text" size="small" @click="handleNewChat" shape="circle">
            <template #icon>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
            </template>
          </Button>
        </div>
      </div>
      <div v-if="showSessions" class="session-items">
        <div v-if="chatStore.isLoadingSessions && chatStore.sessions.length === 0" class="session-loading">{{ t('common.loading') }}</div>
        <div v-else-if="chatStore.sessions.length === 0" class="session-empty">{{ t('chat.noSessions') }}</div>

        <template v-if="pinnedSessions.length > 0">
          <div class="session-group-header session-group-header--static">
            <span class="session-group-label">{{ t('chat.pinned') }}</span>
            <span class="session-group-count">{{ pinnedSessions.length }}</span>
          </div>
          <SessionListItem
            v-for="s in pinnedSessions"
            :key="`pinned-${s.id}`"
            :session="s"
            :active="s.id === chatStore.activeSessionId"
            :live="chatStore.isSessionLive(s.id)"
            :pinned="true"
            :can-delete="s.id !== chatStore.activeSessionId || chatStore.sessions.length > 1"
            @select="handleSessionClick(s.id)"
            @contextmenu="handleContextMenu($event, s.id)"
            @delete="handleDeleteSession(s.id)"
          />
        </template>

        <template v-for="group in groupedSessions" :key="group.source">
          <div class="session-group-header" @click="toggleGroup(group.source)">
            <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="group-chevron" :class="{ collapsed: collapsedGroups.has(group.source) }"><polyline points="9 18 15 12 9 6"/></svg>
            <span class="session-group-label">{{ group.label }}</span>
            <span class="session-group-count">{{ group.sessions.length }}</span>
          </div>
          <template v-if="!collapsedGroups.has(group.source)">
            <SessionListItem
              v-for="s in group.sessions"
              :key="s.id"
              :session="s"
              :active="s.id === chatStore.activeSessionId"
              :live="chatStore.isSessionLive(s.id)"
              :pinned="false"
              :can-delete="s.id !== chatStore.activeSessionId || chatStore.sessions.length > 1"
              @select="handleSessionClick(s.id)"
              @contextmenu="handleContextMenu($event, s.id)"
              @delete="handleDeleteSession(s.id)"
            />
          </template>
        </template>
      </div>
    </aside>

    <Dropdown
      placement="bottomLeft"
      :trigger="['click']"
      :open="showContextMenu"
      :menu="{ items: contextMenuItems, onClick: handleContextMenuClick }"
      @open-change="open => { if (!open) handleClickOutside() }"
    >
      <button class="context-menu-anchor" :style="contextMenuStyle" aria-hidden="true" tabindex="-1" />
    </Dropdown>

    <Modal
      v-model:open="showRenameModal"

      :title="t('chat.renameSession')"
      :ok-text="t('common.ok')"
      :cancel-text="t('common.cancel')"
      @ok="handleRenameConfirm"
    >
      <Input
        ref="renameInputRef"
        v-model:value="renameValue"
        :placeholder="t('chat.enterNewTitle')"
        @keydown.enter="handleRenameConfirm"
      />
    </Modal>

    <div class="chat-main">
      <header class="chat-header">
        <div class="header-left">
          <Button v-if="currentMode === 'chat'" type="text" size="small" @click="showSessions = !showSessions" shape="circle">
            <template #icon>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/></svg>
            </template>
          </Button>
          <span class="header-session-title">{{ headerTitle }}</span>
          <span v-if="activeSessionSource" class="source-badge">{{ getSourceLabel(activeSessionSource) }}</span>
          <span
            v-if="activeGoalState"
            class="goal-badge"
            :class="`goal-badge--${activeGoalState.status}`"
            :title="activeGoalTitle"
          >
            {{ activeGoalLabel }}
          </span>
          <div v-if="activeGoalState" class="goal-actions">
            <Tooltip v-if="activeGoalState.status === 'active'" :title="t('chat.pauseGoal')" trigger="hover">
              <Button type="text" size="small" :disabled="!canPauseGoal" shape="circle" @click="runGoalCommand('pause')">
                <template #icon>
                  <svg class="goal-action-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <rect x="6" y="4" width="4" height="16" rx="1" />
                    <rect x="14" y="4" width="4" height="16" rx="1" />
                  </svg>
                </template>
              </Button>
            </Tooltip>
            <Tooltip v-if="activeGoalState.status === 'paused'" :title="t('chat.resumeGoal')" trigger="hover">
              <Button type="text" size="small" :disabled="!canResumeGoal" shape="circle" @click="runGoalCommand('resume')">
                <template #icon>
                  <svg class="goal-action-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                    <path d="M8 5v14l11-7z" />
                  </svg>
                </template>
              </Button>
            </Tooltip>
            <Tooltip v-if="activeGoalState.status !== 'done'" :title="t('chat.clearGoal')" trigger="hover">
              <Button type="text" size="small" :disabled="!canClearGoal" shape="circle" @click="runGoalCommand('clear')">
                <template #icon>
                  <svg class="goal-action-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="18" y1="6" x2="6" y2="18" />
                    <line x1="6" y1="6" x2="18" y2="18" />
                  </svg>
                </template>
              </Button>
            </Tooltip>
          </div>
        </div>
        <div class="header-actions">
          <AgentSelector v-if="currentMode === 'chat'" :session-id="chatStore.activeSessionId" />
          <div class="chat-mode-toggle">
            <Button
              size="small"
              :type="currentMode === 'chat' ? 'primary' : 'default'"
              :aria-pressed="currentMode === 'chat'"
              @click="handleModeChange('chat')"
            >{{ t('chat.chatMode') }}</Button>
            <Button
              size="small"
              :type="currentMode === 'live' ? 'primary' : 'default'"
              :aria-pressed="currentMode === 'live'"
              @click="handleModeChange('live')"
            >{{ t('chat.liveMode') }}</Button>
          </div>
          <template v-if="currentMode === 'chat'">
            <Tooltip :title="t('chat.copySessionId')" trigger="hover">
              <Button type="text" size="small" @click="copySessionId()" shape="circle">
                <template #icon>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                </template>
              </Button>
            </Tooltip>
            <Button size="small" :shape="isMobile ? 'circle' : 'default'" @click="handleNewChat">
              <template #icon>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
              </template>
              <template v-if="!isMobile">{{ t('chat.newChat') }}</template>
            </Button>
          </template>
        </div>
      </header>

      <template v-if="currentMode === 'chat'">
        <MessageList />
        <ChatInput />
      </template>
      <ConversationMonitorPane v-else :human-only="sessionBrowserPrefsStore.humanOnly" />
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.chat-panel {
  display: flex;
  height: 100%;
  position: relative;
}

.session-list {
  width: 220px;
  border-right: 1px solid $border-color;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  transition: width $transition-normal, opacity $transition-normal;
  overflow: hidden;

  &.collapsed {
    width: 0;
    border-right: none;
    opacity: 0;
    pointer-events: none;
  }

  @media (max-width: $breakpoint-mobile) {
    position: absolute;
    left: 0;
    top: 0;
    height: 100%;
    z-index: 10;
    background: $bg-card;
    box-shadow: 2px 0 8px rgba(0, 0, 0, 0.1);
    width: 280px;

    &.collapsed {
      transform: translateX(-100%);
      opacity: 0;
    }
  }
}

@media (max-width: $breakpoint-mobile) {
  .session-close-btn {
    display: flex;
  }

.session-backdrop {
    position: absolute;
    inset: 0;
    background: rgba(0, 0, 0, 0.4);
    z-index: 9;
    opacity: 0;
    pointer-events: none;
    transition: opacity $transition-fast;

    &.active {
      opacity: 1;
      pointer-events: auto;
    }
  }
}

.session-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px;
  flex-shrink: 0;
}

.session-list-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.session-close-btn {
  display: none;
  border: none;
  background: none;
  cursor: pointer;
  color: $text-secondary;
  padding: 4px;
  border-radius: $radius-sm;

  &:hover {
    background: rgba($accent-primary, 0.06);
  }
}

.session-list-title {
  font-size: 12px;
  font-weight: 600;
  color: $text-muted;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.session-group-header {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px 4px;
  cursor: pointer;
  user-select: none;
}

.session-group-header--static {
  cursor: default;
}

.group-chevron {
  flex-shrink: 0;
  transition: transform 0.15s ease;
  transform: rotate(90deg);

  &.collapsed {
    transform: rotate(0deg);
  }
}

.session-group-label {
  font-size: 10px;
  font-weight: 600;
  color: $text-muted;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.session-group-count {
  font-size: 10px;
  color: $text-muted;
  font-weight: 400;
}

.session-items {
  flex: 1;
  overflow-y: auto;
  padding: 0 6px 12px;
}

.session-loading,
.session-empty {
  padding: 16px 10px;
  font-size: 12px;
  color: $text-muted;
  text-align: center;
}

:deep(.session-item) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 8px 10px;
  border: none;
  background: none;
  border-radius: $radius-sm;
  cursor: pointer;
  text-align: left;
  color: $text-secondary;
  transition: all $transition-fast;
  margin-bottom: 2px;

  &:hover {
    background: rgba($accent-primary, 0.06);
    color: $text-primary;

    .session-item-delete {
      opacity: 1;
    }
  }

  &.active {
    background: rgba(var(--accent-primary-rgb), 0.12);
    color: $text-primary;
    font-weight: 500;
  }

  &.active .session-item-title {
    color: $accent-primary;
  }

  &.live .session-item-title {
    color: $accent-primary;
  }
}

:deep(.session-item-content) {
  flex: 1;
  overflow: hidden;
}

:deep(.session-item-title-row) {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

:deep(.session-item-title) {
  display: block;
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

:deep(.session-item-active-indicator) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: $accent-primary;
}

:deep(.session-item-active-spinner) {
  animation: session-spin 1.1s linear infinite;
}

:deep(.session-item-live-badge) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 10px;
  line-height: 16px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: $accent-primary;
  background: rgba(var(--accent-primary-rgb), 0.10);
}

:deep(.live-dot) {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: $accent-primary;
  animation: live-pulse 2s ease-in-out infinite;
}

@keyframes live-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(0.7); }
}

:deep(.session-item-pin) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: $accent-primary;
}

:deep(.session-item-time) {
  font-size: 11px;
  color: $text-muted;
}

:deep(.session-item-meta) {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
}

:deep(.session-item-model) {
  font-size: 10px;
  color: $accent-primary;
  background: rgba($accent-primary, 0.08);
  padding: 0 5px;
  border-radius: 3px;
  line-height: 16px;
  flex-shrink: 0;
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.session-item-delete) {
  flex-shrink: 0;
  opacity: 0.5;
  padding: 2px;
  border: none;
  background: none;
  color: $text-muted;
  cursor: pointer;
  border-radius: 3px;
  transition: all $transition-fast;

  &:hover {
    color: $error;
    background: rgba($error, 0.1);
  }
}

@keyframes session-spin {
  from {
    transform: rotate(0deg);
  }

  to {
    transform: rotate(360deg);
  }
}

.context-menu-anchor {
  position: fixed;
  z-index: 1000;
  width: 1px;
  height: 1px;
  padding: 0;
  pointer-events: none;
  background: transparent;
  border: 0;
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 21px 20px;
  border-bottom: 1px solid $border-color;
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  overflow: hidden;
  flex: 1;
  min-width: 0;
}

.header-session-title {
  font-size: 16px;
  font-weight: 600;
  color: $text-primary;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.source-badge {
  font-size: 10px;
  color: $text-muted;
  background: rgba($text-muted, 0.12);
  padding: 1px 7px;
  border-radius: 8px;
  flex-shrink: 0;
  white-space: nowrap;
  line-height: 16px;
}

.goal-badge {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 10px;
  color: $text-secondary;
  background: rgba(var(--accent-primary-rgb), 0.08);
  border: 1px solid $border-color;
  padding: 1px 7px;
  border-radius: 8px;
  flex-shrink: 0;
  line-height: 16px;

  &--active {
    color: $success;
    background: rgba(var(--success-rgb), 0.08);
  }

  &--paused {
    color: $warning;
    background: rgba(var(--warning-rgb), 0.08);
  }

  &--done {
    color: $text-muted;
  }
}

.goal-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

.goal-action-icon {
  width: 12px;
  height: 12px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.chat-mode-toggle {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-right: 4px;
}

@media (max-width: $breakpoint-mobile) {
  .chat-header {
    padding: 16px 12px 16px 52px;
  }
}
</style>
