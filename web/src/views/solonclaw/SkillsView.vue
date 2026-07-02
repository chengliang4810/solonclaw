<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { Input } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import SkillList from '@/components/solonclaw/skills/SkillList.vue'
import SkillDetail from '@/components/solonclaw/skills/SkillDetail.vue'
import { fetchSkills, type SkillCategory } from '@/api/solonclaw/skills'

const { t } = useI18n()
const categories = ref<SkillCategory[]>([])
const loading = ref(false)
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
