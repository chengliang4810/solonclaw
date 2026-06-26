<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { NButton, NInput, NTag, useMessage } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import SkillList from '@/components/solonclaw/skills/SkillList.vue'
import SkillDetail from '@/components/solonclaw/skills/SkillDetail.vue'
import { fetchSkills, type SkillCategory } from '@/api/solonclaw/skills'
import {
  fetchCuratorImprovements,
  ignoreCuratorSuggestion,
  markCuratorSuggestionApplied,
  type CuratorImprovement,
} from '@/api/solonclaw/curator'

const { t } = useI18n()
const message = useMessage()
const categories = ref<SkillCategory[]>([])
const improvements = ref<CuratorImprovement[]>([])
const loading = ref(false)
const improvementsLoading = ref(false)
const actionKey = ref('')
const selectedCategory = ref('')
const selectedSkill = ref('')
const searchQuery = ref('')
const showSidebar = ref(true)
let mobileQuery: MediaQueryList | null = null

function handleMobileChange(e: MediaQueryListEvent | MediaQueryList) {
  showSidebar.value = !e.matches
}

onMounted(() => {
  mobileQuery = window.matchMedia('(max-width: 768px)')
  handleMobileChange(mobileQuery)
  mobileQuery.addEventListener('change', handleMobileChange)
  loadSkills()
  loadImprovements()
})

onUnmounted(() => {
  mobileQuery?.removeEventListener('change', handleMobileChange)
})

async function loadSkills() {
  loading.value = true
  try {
    categories.value = await fetchSkills()
  } catch (err: any) {
    console.error('Failed to load skills:', err)
    message.error(err?.message || t('skills.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function loadImprovements() {
  improvementsLoading.value = true
  try {
    improvements.value = await fetchCuratorImprovements(20)
  } catch (err: any) {
    message.error(err?.message || t('skills.curatorActionFailed'))
  } finally {
    improvementsLoading.value = false
  }
}

async function updateSuggestion(item: CuratorImprovement, action: 'apply' | 'ignore') {
  const key = `${action}:${item.improvement_id}`
  actionKey.value = key
  try {
    const skill = item.skill_name || ''
    const suggestion = item.summary || item.action || ''
    if (action === 'apply') {
      await markCuratorSuggestionApplied(skill, suggestion)
      message.success(t('skills.curatorMarkedApplied'))
    } else {
      await ignoreCuratorSuggestion(skill, suggestion)
      message.success(t('skills.curatorMarkedIgnored'))
    }
    await loadImprovements()
  } catch (err: any) {
    message.error(err.message || t('skills.curatorActionFailed'))
  } finally {
    actionKey.value = ''
  }
}

function handleSelect(category: string, skill: string) {
  selectedCategory.value = category
  selectedSkill.value = skill
  if (window.innerWidth <= 768) {
    showSidebar.value = false
  }
}
</script>

<template>
  <div class="skills-view">
    <header class="page-header">
      <div style="display: flex; align-items: center; gap: 8px;">
        <h2 class="header-title">{{ t('skills.title') }}</h2>
        <button v-if="!showSidebar" class="sidebar-toggle" @click="showSidebar = true">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
        </button>
      </div>
      <NInput
        v-model:value="searchQuery"
        :placeholder="t('skills.searchPlaceholder')"
        size="small"
        clearable
        style="width: 160px"
      />
    </header>

    <div class="skills-content">
      <div v-if="loading && categories.length === 0" class="skills-loading">{{ t('common.loading') }}</div>
      <div v-else class="skills-layout">
          <div class="mobile-backdrop" :class="{ active: showSidebar }" @click="showSidebar = false" />
          <div v-if="showSidebar" class="skills-sidebar">
            <SkillList
              :categories="categories"
              :selected-skill="selectedCategory && selectedSkill ? `${selectedCategory}/${selectedSkill}` : null"
              :search-query="searchQuery"
              @select="handleSelect"
            />
            <section class="curator-panel">
              <div class="curator-header">
                <span>{{ t('skills.curatorImprovements') }}</span>
                <NButton size="tiny" quaternary :loading="improvementsLoading" @click="loadImprovements">
                  {{ t('common.refresh') }}
                </NButton>
              </div>
              <div v-if="improvements.length" class="curator-list">
                <article v-for="item in improvements" :key="item.improvement_id" class="curator-item">
                  <div class="curator-item__head">
                    <strong>{{ item.skill_name || '-' }}</strong>
                    <NTag v-if="item.needs_review" size="small" type="warning">{{ t('skills.needsReview') }}</NTag>
                  </div>
                  <p>{{ item.summary || item.action || '-' }}</p>
                  <div class="curator-actions">
                    <NButton
                      size="tiny"
                      quaternary
                      type="primary"
                      :loading="actionKey === `apply:${item.improvement_id}`"
                      @click="updateSuggestion(item, 'apply')"
                    >
                      {{ t('skills.markApplied') }}
                    </NButton>
                    <NButton
                      size="tiny"
                      quaternary
                      :loading="actionKey === `ignore:${item.improvement_id}`"
                      @click="updateSuggestion(item, 'ignore')"
                    >
                      {{ t('skills.ignoreSuggestion') }}
                    </NButton>
                  </div>
                </article>
              </div>
              <div v-else class="curator-empty">{{ t('skills.noCuratorImprovements') }}</div>
            </section>
          </div>
          <div class="skills-main">
            <SkillDetail
              v-if="selectedCategory && selectedSkill"
              :category="selectedCategory"
              :skill="selectedSkill"
            />
            <div v-else class="empty-detail">
              <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" opacity="0.2">
                <polygon points="12 2 2 7 12 12 22 7 12 2" />
                <polyline points="2 17 12 22 22 17" />
                <polyline points="2 12 12 17 22 12" />
              </svg>
              <span>{{ t('skills.selectHint') }}</span>
            </div>
          </div>
        </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.skills-view {
  height: calc(100 * var(--vh));
  display: flex;
  flex-direction: column;
}

.search-input {
  width: 100px;

  @media (max-width: $breakpoint-mobile) {
    width: 100%;
  }
}

.skills-content {
  flex: 1;
  overflow: hidden;
}

.skills-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: 13px;
  color: $text-muted;
}

.skills-layout {
  display: flex;
  height: 100%;
}

.skills-sidebar {
  width: 280px;
  border-right: 1px solid $border-color;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
}

.curator-panel {
  border-top: 1px solid $border-color;
  padding: 10px;
  flex-shrink: 0;
  max-height: 42%;
  overflow-y: auto;
}

.curator-header,
.curator-item__head,
.curator-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.curator-header {
  color: $text-muted;
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 8px;
}

.curator-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.curator-item {
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  padding: 8px;

  strong {
    color: $text-primary;
    font-size: 12px;
    font-weight: 600;
  }

  p {
    color: $text-secondary;
    font-size: 12px;
    line-height: 1.4;
    margin: 6px 0;
  }
}

.curator-actions {
  justify-content: flex-start;
}

.curator-empty {
  color: $text-muted;
  font-size: 12px;
  padding: 10px 0;
}

.skills-main {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  min-width: 0;
}

.sidebar-toggle {
  display: none;
  border: none;
  background: none;
  cursor: pointer;
  color: $text-secondary;
  padding: 4px;
  border-radius: $radius-sm;

  &:hover {
    background: rgba(var(--accent-primary-rgb), 0.06);
  }
}

@media (max-width: $breakpoint-mobile) {
  .sidebar-toggle {
    display: flex;
  }

  .skills-sidebar {
    position: absolute;
    left: 0;
    top: 0;
    height: 100%;
    z-index: 10;
    background: $bg-card;
    box-shadow: 2px 0 8px rgba(0, 0, 0, 0.1);
  }

  .skills-layout {
    position: relative;
  }

  .mobile-backdrop {
    display: block;
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

.empty-detail {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: $text-muted;
  font-size: 13px;
}
</style>
