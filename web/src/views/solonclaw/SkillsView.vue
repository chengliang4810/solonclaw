<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { Button, Input, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import SkillList from '@/components/solonclaw/skills/SkillList.vue'
import SkillDetail from '@/components/solonclaw/skills/SkillDetail.vue'
import {
  applyCuratorSuggestion,
  fetchCuratorImprovements,
  ignoreCuratorSuggestion,
  runCurator,
  type CuratorImprovement,
} from '@/api/solonclaw/curator'
import { fetchSkills, type SkillCategory } from '@/api/solonclaw/skills'

const { t } = useI18n()
const categories = ref<SkillCategory[]>([])
const improvements = ref<CuratorImprovement[]>([])
const loading = ref(false)
const curatorLoading = ref(false)
const curatorActionId = ref('')
const selectedCategory = ref('')
const selectedSkill = ref('')
const searchQuery = ref('')
const showSidebar = ref(true)
let mobileQuery: MediaQueryList | null = null

const pendingImprovements = computed(() => improvements.value.filter(item => item.needs_review !== false))

function handleMobileChange(e: MediaQueryListEvent | MediaQueryList) {
  showSidebar.value = !e.matches
}

onMounted(() => {
  mobileQuery = window.matchMedia('(max-width: 768px)')
  handleMobileChange(mobileQuery)
  mobileQuery.addEventListener('change', handleMobileChange)
  loadSkills()
  loadCuratorImprovements()
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
  } finally {
    loading.value = false
  }
}

async function loadCuratorImprovements() {
  curatorLoading.value = true
  try {
    improvements.value = await fetchCuratorImprovements()
  } catch (err: any) {
    message.error(`${t('skills.curatorLoadFailed')}: ${err.message}`)
  } finally {
    curatorLoading.value = false
  }
}

async function runCuratorNow() {
  curatorLoading.value = true
  try {
    await runCurator(true)
    improvements.value = await fetchCuratorImprovements()
    message.success(t('skills.curatorRunComplete'))
  } catch (err: any) {
    message.error(`${t('skills.curatorRunFailed')}: ${err.message}`)
  } finally {
    curatorLoading.value = false
  }
}

async function resolveCuratorSuggestion(item: CuratorImprovement, action: 'apply' | 'ignore') {
  const suggestion = item.summary || item.action || item.improvement_id
  curatorActionId.value = `${action}:${item.improvement_id}`
  try {
    if (action === 'apply') {
      await applyCuratorSuggestion(item.skill_name, suggestion)
      message.success(t('skills.curatorApplied'))
    } else {
      await ignoreCuratorSuggestion(item.skill_name, suggestion)
      message.success(t('skills.curatorIgnored'))
    }
    improvements.value = improvements.value.filter(current => current.improvement_id !== item.improvement_id)
  } catch (err: any) {
    message.error(`${t('skills.curatorActionFailed')}: ${err.message}`)
  } finally {
    curatorActionId.value = ''
  }
}

function formatCuratorTime(value?: number) {
  if (!value) return ''
  return new Date(value).toLocaleString()
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
      <Input
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
          </div>
          <div class="skills-main">
            <section class="curator-panel">
              <div class="curator-header">
                <div>
                  <h3>{{ t('skills.curatorTitle') }}</h3>
                  <p>{{ t('skills.curatorDescription') }}</p>
                </div>
                <div class="curator-actions">
                  <Button size="small" :loading="curatorLoading" @click="loadCuratorImprovements">
                    {{ t('skills.refresh') }}
                  </Button>
                  <Button size="small" type="primary" :loading="curatorLoading" @click="runCuratorNow">
                    {{ t('skills.curatorRun') }}
                  </Button>
                </div>
              </div>

              <div v-if="pendingImprovements.length === 0" class="curator-empty">
                {{ curatorLoading ? t('common.loading') : t('skills.curatorEmpty') }}
              </div>
              <div v-else class="curator-list">
                <article v-for="item in pendingImprovements" :key="item.improvement_id" class="curator-item">
                  <div class="curator-item-main">
                    <div class="curator-item-title">
                      <span>{{ item.skill_name || t('skills.curatorUnknownSkill') }}</span>
                      <small v-if="item.action">{{ item.action }}</small>
                    </div>
                    <p>{{ item.summary || item.improvement_id }}</p>
                    <div class="curator-item-meta">
                      <span v-if="item.created_at">{{ formatCuratorTime(item.created_at) }}</span>
                      <span v-if="item.run_id">{{ item.run_id }}</span>
                    </div>
                  </div>
                  <div class="curator-item-actions">
                    <Button
                      size="small"
                      :loading="curatorActionId === `ignore:${item.improvement_id}`"
                      @click="resolveCuratorSuggestion(item, 'ignore')"
                    >
                      {{ t('skills.curatorIgnore') }}
                    </Button>
                    <Button
                      size="small"
                      type="primary"
                      :loading="curatorActionId === `apply:${item.improvement_id}`"
                      @click="resolveCuratorSuggestion(item, 'apply')"
                    >
                      {{ t('skills.curatorApply') }}
                    </Button>
                  </div>
                </article>
              </div>
            </section>

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

.skills-main {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  min-width: 0;
}

.curator-panel {
  border-bottom: 1px solid $border-color;
  padding-bottom: 14px;
  margin-bottom: 14px;
}

.curator-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;

  h3 {
    margin: 0;
    font-size: 14px;
    color: $text-primary;
  }

  p {
    margin: 4px 0 0;
    color: $text-muted;
    font-size: 12px;
  }
}

.curator-actions,
.curator-item-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.curator-empty {
  margin-top: 10px;
  color: $text-muted;
  font-size: 12px;
}

.curator-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
}

.curator-item {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  padding: 10px 12px;
  background: $bg-secondary;
}

.curator-item-main {
  min-width: 0;
  flex: 1;

  p {
    margin: 6px 0;
    color: $text-secondary;
    font-size: 13px;
    line-height: 1.5;
  }
}

.curator-item-title,
.curator-item-meta {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.curator-item-title {
  color: $text-primary;
  font-weight: 600;
  font-size: 13px;

  small {
    color: $text-muted;
    font-weight: 400;
  }
}

.curator-item-meta {
  color: $text-muted;
  font-size: 11px;
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

  .curator-header,
  .curator-item {
    flex-direction: column;
  }

  .curator-actions,
  .curator-item-actions {
    width: 100%;
    justify-content: flex-end;
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
