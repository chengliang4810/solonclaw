<script setup lang="ts">
import { computed, ref } from 'vue'
import { Button, Checkbox, Input, Select, Spin, Tag, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import {
  fetchProfileModelChoices,
  searchProfileHubSkills,
  type ProfileHubSkill,
  type ProfileMcpServerCreate,
  type ProfileModelChoice,
} from '@/api/solonclaw/profiles'
import { fetchSkills } from '@/api/solonclaw/skills'
import { useProfilesStore } from '@/stores/solonclaw/profiles'

const PROFILE_NAME_RE = /^[a-z0-9][a-z0-9_-]{0,63}$/

type BuilderStep = 'identity' | 'model' | 'skills' | 'mcp' | 'review'

interface BuilderSkill {
  name: string
  description: string
  category: string
  enabled: boolean
}

const { t } = useI18n()
const router = useRouter()
const profilesStore = useProfilesStore()

const stepOrder: BuilderStep[] = ['identity', 'model', 'skills', 'mcp', 'review']
const currentStep = ref<BuilderStep>('identity')
const name = ref('')
const description = ref('')
const modelChoices = ref<ProfileModelChoice[] | null>(null)
const modelLoading = ref(false)
const modelProvider = ref('')
const modelName = ref('')
const skills = ref<BuilderSkill[] | null>(null)
const skillsLoading = ref(false)
const keepAllSkills = ref(true)
const keptSkills = ref<Set<string>>(new Set())
const skillFilter = ref('')
const hubQuery = ref('')
const hubResults = ref<ProfileHubSkill[]>([])
const hubSkills = ref<ProfileHubSkill[]>([])
const hubSearching = ref(false)
const mcpServers = ref<ProfileMcpServerCreate[]>([])
const mcpDraftName = ref('')
const mcpDraftUrl = ref('')
const mcpDraftCommand = ref('')
const mcpDraftArgs = ref('')

const steps = computed(() => stepOrder.map(id => ({ id, label: t(`profiles.builder.steps.${id}`) })))
const currentStepIndex = computed(() => stepOrder.indexOf(currentStep.value))
const nameValid = computed(() => PROFILE_NAME_RE.test(name.value.trim()))
const selectedModel = computed(() => {
  if (!modelProvider.value || !modelName.value) return undefined
  return modelChoices.value?.find(choice =>
    choice.provider === modelProvider.value && choice.model === modelName.value,
  )
})
const modelProviderOptions = computed(() => Array.from(
  new Map((modelChoices.value || []).map(choice => [choice.provider, choice.providerLabel])).entries(),
).map(([value, label]) => ({ value, label })))
const modelOptions = computed(() => (modelChoices.value || [])
  .filter(choice => choice.provider === modelProvider.value)
  .map(choice => ({ label: choice.model, value: choice.model })))
const filteredSkills = computed(() => {
  const filter = skillFilter.value.trim().toLowerCase()
  if (!filter) return skills.value || []
  return (skills.value || []).filter(skill =>
    skill.name.toLowerCase().includes(filter)
      || skill.description.toLowerCase().includes(filter)
      || skill.category.toLowerCase().includes(filter),
  )
})

/** 切换 Provider 后默认选择其第一个已登记模型。 */
function handleModelProviderChange(value?: string): void {
  modelProvider.value = value || ''
  modelName.value = modelProvider.value ? modelOptions.value[0]?.value || '' : ''
}

async function loadModels(): Promise<void> {
  if (modelChoices.value !== null || modelLoading.value) return
  modelLoading.value = true
  try {
    modelChoices.value = await fetchProfileModelChoices()
  } catch (error) {
    modelChoices.value = []
    message.error(error instanceof Error ? error.message : t('profiles.builder.modelLoadFailed'))
  } finally {
    modelLoading.value = false
  }
}

async function loadSkills(): Promise<void> {
  if (skills.value !== null || skillsLoading.value) return
  skillsLoading.value = true
  try {
    const categories = await fetchSkills()
    skills.value = categories.flatMap(category => category.skills.map(skill => ({
      name: skill.canonicalName,
      description: skill.description || '',
      category: category.name,
      enabled: skill.enabled !== false,
    })))
    keptSkills.value = new Set(skills.value.filter(skill => skill.enabled).map(skill => skill.name))
  } catch (error) {
    skills.value = []
    message.error(error instanceof Error ? error.message : t('profiles.builder.skillsLoadFailed'))
  } finally {
    skillsLoading.value = false
  }
}

function prepareStep(step: BuilderStep): void {
  if (step === 'model') void loadModels()
  if (step === 'skills') void loadSkills()
}

function selectStep(step: BuilderStep): void {
  if (step !== 'identity' && !nameValid.value) return
  currentStep.value = step
  prepareStep(step)
}

function goBack(): void {
  const previous = stepOrder[Math.max(0, currentStepIndex.value - 1)]
  if (previous) selectStep(previous)
}

function goNext(): void {
  const next = stepOrder[Math.min(stepOrder.length - 1, currentStepIndex.value + 1)]
  if (next) selectStep(next)
}

function toggleKeptSkill(skillName: string): void {
  const next = new Set(keptSkills.value)
  if (next.has(skillName)) next.delete(skillName)
  else next.add(skillName)
  keptSkills.value = next
}

async function searchHub(): Promise<void> {
  const query = hubQuery.value.trim()
  if (!query || hubSearching.value) return
  hubSearching.value = true
  try {
    const response = await searchProfileHubSkills(query, 'all', 20)
    hubResults.value = response.results || []
  } catch (error) {
    hubResults.value = []
    message.error(error instanceof Error ? error.message : t('profiles.builder.hubSearchFailed'))
  } finally {
    hubSearching.value = false
  }
}

function addHubSkill(skill: ProfileHubSkill): void {
  if (hubSkills.value.some(item => item.identifier === skill.identifier)) return
  hubSkills.value = [...hubSkills.value, skill]
}

function removeHubSkill(identifier: string): void {
  hubSkills.value = hubSkills.value.filter(skill => skill.identifier !== identifier)
}

function addMcpServer(): void {
  const serverName = mcpDraftName.value.trim()
  const url = mcpDraftUrl.value.trim()
  const command = mcpDraftCommand.value.trim()
  if (!serverName) {
    message.warning(t('profiles.builder.mcpNameRequired'))
    return
  }
  if (!url && !command) {
    message.warning(t('profiles.builder.mcpEndpointRequired'))
    return
  }
  const server: ProfileMcpServerCreate = { name: serverName }
  if (url) server.url = url
  if (command) {
    server.command = command
    const args = mcpDraftArgs.value.trim()
    if (args) server.args = args.split(/\s+/)
  }
  mcpServers.value = [...mcpServers.value.filter(item => item.name !== serverName), server]
  mcpDraftName.value = ''
  mcpDraftUrl.value = ''
  mcpDraftCommand.value = ''
  mcpDraftArgs.value = ''
}

function removeMcpServer(serverName: string): void {
  mcpServers.value = mcpServers.value.filter(server => server.name !== serverName)
}

async function createProfile(): Promise<void> {
  const profileName = name.value.trim()
  if (!PROFILE_NAME_RE.test(profileName)) {
    currentStep.value = 'identity'
    message.warning(t('profiles.invalidName'))
    return
  }
  try {
    const result = await profilesStore.createProfile({
      name: profileName,
      clone_from: null,
      description: description.value.trim() || undefined,
      provider: selectedModel.value?.provider,
      model: selectedModel.value?.model,
      mcp_servers: mcpServers.value.length ? mcpServers.value : undefined,
      keep_skills: keepAllSkills.value ? undefined : Array.from(keptSkills.value),
      hub_skills: hubSkills.value.length ? hubSkills.value.map(skill => skill.identifier) : undefined,
    })
    const pending = (result.hub_installs || []).filter(install => install.pid).length
    message.success(pending
      ? t('profiles.builder.createPendingSuccess', { name: profileName, count: pending })
      : t('profiles.createSuccess', { name: profileName }))
    await router.push({ name: 'solonclaw.profiles' })
  } catch (error) {
    message.error(error instanceof Error ? error.message : t('profiles.createFailed'))
  }
}

function cancel(): void {
  void router.push({ name: 'solonclaw.profiles' })
}
</script>

<template>
  <div class="profile-builder-view">
    <header class="builder-header">
      <div>
        <h2>{{ t('profiles.builder.title') }}</h2>
        <p>{{ t('profiles.builder.subtitle') }}</p>
      </div>
      <Button @click="cancel">{{ t('common.cancel') }}</Button>
    </header>

    <nav class="builder-steps" :aria-label="t('profiles.builder.progress')">
      <button
        v-for="(step, index) in steps"
        :key="step.id"
        type="button"
        :class="{ active: step.id === currentStep, complete: index < currentStepIndex }"
        :disabled="index > 0 && !nameValid"
        @click="selectStep(step.id)"
      >
        <span>{{ index + 1 }}</span>
        {{ step.label }}
      </button>
    </nav>

    <section class="builder-panel">
      <div v-if="currentStep === 'identity'" class="builder-section">
        <div class="field-group">
          <label for="profile-builder-name">{{ t('profiles.name') }}</label>
          <Input id="profile-builder-name" v-model:value="name" placeholder="coder" autofocus @keyup.enter="nameValid && goNext()" />
          <p v-if="name && !nameValid" class="field-error">{{ t('profiles.invalidName') }}</p>
        </div>
        <div class="field-group">
          <label for="profile-builder-description">{{ t('profiles.builder.descriptionOptional') }}</label>
          <Input id="profile-builder-description" v-model:value="description" :placeholder="t('profiles.descriptionPlaceholder')" />
        </div>
      </div>

      <div v-else-if="currentStep === 'model'" class="builder-section">
        <p class="section-hint">{{ t('profiles.builder.modelHint') }}</p>
        <Spin :spinning="modelLoading">
          <div class="model-picker">
            <Select
              v-model:value="modelProvider"
              :options="modelProviderOptions"
              :placeholder="t('models.chooseProvider')"
              allow-clear
              @change="handleModelProviderChange"
            />
            <Select
              v-model:value="modelName"
              :options="modelOptions"
              :placeholder="t('profiles.builder.modelDefault')"
              :disabled="!modelProvider"
            />
            <p v-if="!modelLoading && modelChoices?.length === 0" class="empty-copy">
              {{ t('profiles.builder.modelEmpty') }}
            </p>
          </div>
        </Spin>
      </div>

      <div v-else-if="currentStep === 'skills'" class="builder-section">
        <label class="checkbox-row">
          <Checkbox v-model:checked="keepAllSkills" />
          <span>{{ t('profiles.builder.keepAllSkills') }}</span>
        </label>
        <div v-if="!keepAllSkills" class="skill-picker">
          <p class="section-hint">{{ t('profiles.builder.skillHint') }}</p>
          <Input v-model:value="skillFilter" allow-clear :placeholder="t('profiles.builder.skillFilter')" />
          <Spin :spinning="skillsLoading">
            <div class="choice-list skill-list">
              <label v-for="skill in filteredSkills" :key="skill.name" class="skill-choice">
                <Checkbox :checked="keptSkills.has(skill.name)" @update:checked="toggleKeptSkill(skill.name)" />
                <span>
                  <strong>{{ skill.name }}</strong>
                  <Tag v-if="skill.category" class="skill-category">{{ skill.category }}</Tag>
                  <small v-if="skill.description">{{ skill.description }}</small>
                </span>
              </label>
              <p v-if="!skillsLoading && skills?.length === 0" class="empty-copy">
                {{ t('profiles.builder.skillsEmpty') }}
              </p>
            </div>
          </Spin>
        </div>

        <div class="hub-section">
          <label for="profile-builder-hub">{{ t('profiles.builder.hubTitle') }}</label>
          <div class="inline-form">
            <Input
              id="profile-builder-hub"
              v-model:value="hubQuery"
              :placeholder="t('profiles.builder.hubPlaceholder')"
              @keyup.enter="searchHub"
            />
            <Button :loading="hubSearching" @click="searchHub">{{ t('common.search') }}</Button>
          </div>
          <div v-if="hubResults.length" class="hub-results">
            <div v-for="skill in hubResults" :key="skill.identifier" class="hub-result">
              <div>
                <strong>{{ skill.name }}</strong>
                <Tag>{{ skill.source }}</Tag>
                <small v-if="skill.description">{{ skill.description }}</small>
              </div>
              <Button size="small" :disabled="hubSkills.some(item => item.identifier === skill.identifier)" @click="addHubSkill(skill)">
                {{ t('common.add') }}
              </Button>
            </div>
          </div>
          <div v-if="hubSkills.length" class="selected-tags">
            <span v-for="skill in hubSkills" :key="skill.identifier" class="removable-tag">
              {{ skill.name }}
              <button type="button" :aria-label="t('profiles.builder.removeHubSkill', { name: skill.name })" @click="removeHubSkill(skill.identifier)">&times;</button>
            </span>
          </div>
        </div>
      </div>

      <div v-else-if="currentStep === 'mcp'" class="builder-section">
        <p class="section-hint">{{ t('profiles.builder.mcpHint') }}</p>
        <div class="mcp-fields">
          <Input v-model:value="mcpDraftName" :placeholder="t('profiles.builder.mcpName')" />
          <Input v-model:value="mcpDraftUrl" :placeholder="t('profiles.builder.mcpUrl')" />
          <Input v-model:value="mcpDraftCommand" :placeholder="t('profiles.builder.mcpCommand')" />
          <Input v-model:value="mcpDraftArgs" :placeholder="t('profiles.builder.mcpArgs')" @keyup.enter="addMcpServer" />
        </div>
        <Button @click="addMcpServer">{{ t('profiles.builder.addMcp') }}</Button>
        <div v-if="mcpServers.length" class="mcp-list">
          <div v-for="server in mcpServers" :key="server.name">
            <span>
              <strong>{{ server.name }}</strong>
              <small>{{ server.url || `${server.command || ''} ${(server.args || []).join(' ')}` }}</small>
            </span>
            <Button size="small" danger @click="removeMcpServer(server.name)">{{ t('common.remove') }}</Button>
          </div>
        </div>
      </div>

      <div v-else class="builder-section review-section">
        <dl>
          <div><dt>{{ t('profiles.name') }}</dt><dd>{{ name.trim() || '-' }}</dd></div>
          <div><dt>{{ t('profiles.description') }}</dt><dd>{{ description.trim() || '-' }}</dd></div>
          <div><dt>{{ t('profiles.model') }}</dt><dd>{{ selectedModel?.label || t('profiles.builder.modelDefault') }}</dd></div>
          <div>
            <dt>{{ t('profiles.skills') }}</dt>
            <dd>{{ keepAllSkills ? t('profiles.builder.fullSkillBundle') : t('profiles.builder.keptSkillCount', { count: keptSkills.size }) }}</dd>
          </div>
          <div><dt>{{ t('profiles.builder.hubTitle') }}</dt><dd>{{ hubSkills.map(skill => skill.name).join(', ') || t('common.none') }}</dd></div>
          <div><dt>{{ t('profiles.builder.mcpServers') }}</dt><dd>{{ mcpServers.map(server => server.name).join(', ') || t('common.none') }}</dd></div>
        </dl>
      </div>
    </section>

    <footer class="builder-footer">
      <Button :disabled="currentStepIndex === 0" @click="goBack">{{ t('common.previous') }}</Button>
      <Button
        v-if="currentStep !== 'review'"
        type="primary"
        :disabled="currentStep === 'identity' && !nameValid"
        @click="goNext"
      >
        {{ t('common.next') }}
      </Button>
      <Button v-else type="primary" :loading="profilesStore.mutating" :disabled="!nameValid" @click="createProfile">
        {{ t('profiles.builder.createProfile') }}
      </Button>
    </footer>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.profile-builder-view {
  width: min(860px, 100%);
  margin: 0 auto;
  padding: 24px;
  color: $text-primary;
}

.builder-header,
.builder-footer,
.inline-form,
.checkbox-row,
.hub-result,
.mcp-list > div {
  display: flex;
  align-items: center;
}

.builder-header {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 22px;

  h2 {
    margin: 0;
    font-size: 22px;
  }

  p {
    margin: 4px 0 0;
    color: $text-secondary;
    font-size: 13px;
  }
}

.builder-steps {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 6px;
  margin-bottom: 16px;

  button {
    min-width: 0;
    min-height: 40px;
    padding: 7px 8px;
    border: 1px solid $border-color;
    border-radius: 6px;
    color: $text-secondary;
    background: transparent;
    font-size: 12px;
    cursor: pointer;

    span {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 20px;
      height: 20px;
      margin-right: 4px;
      border-radius: 50%;
      background: rgba(var(--accent-primary-rgb), 0.1);
    }

    &.active {
      border-color: $accent-primary;
      color: $accent-primary;
      background: rgba(var(--accent-primary-rgb), 0.06);
    }

    &.complete {
      color: $text-primary;
    }

    &:disabled {
      opacity: 0.45;
      cursor: not-allowed;
    }
  }
}

.builder-panel {
  min-height: 390px;
  padding: 22px;
  border: 1px solid $border-color;
  border-radius: 8px;
  background: $bg-primary;
}

.builder-section,
.field-group,
.skill-picker,
.hub-section {
  display: grid;
  gap: 12px;
}

.field-group {
  gap: 6px;

  label,
  & > span {
    font-size: 13px;
    font-weight: 600;
  }
}

.field-error {
  margin: 0;
  color: $error;
  font-size: 12px;
}

.section-hint,
.empty-copy {
  margin: 0;
  color: $text-secondary;
  font-size: 12px;
}

.choice-list {
  max-height: 285px;
  overflow: auto;
  border: 1px solid $border-color;
  border-radius: 6px;

  & > button {
    display: block;
    width: 100%;
    padding: 9px 12px;
    border: 0;
    border-bottom: 1px solid $border-color;
    color: $text-primary;
    background: transparent;
    text-align: left;
    cursor: pointer;

    &:last-child {
      border-bottom: 0;
    }

    &.selected {
      color: $accent-primary;
      background: rgba(var(--accent-primary-rgb), 0.08);
    }
  }
}

.model-picker {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.checkbox-row {
  gap: 8px;
  font-size: 13px;
}

.skill-list {
  max-height: 230px;
}

.skill-choice {
  display: flex;
  gap: 8px;
  padding: 8px 10px;
  border-bottom: 1px solid $border-color;
  cursor: pointer;

  &:last-child {
    border-bottom: 0;
  }

  & > span:last-child {
    min-width: 0;
    flex: 1;
  }

  small {
    display: block;
    margin-top: 3px;
    color: $text-secondary;
  }
}

.skill-category {
  margin-left: 6px;
}

.hub-section {
  padding-top: 16px;
  border-top: 1px solid $border-color;

  & > label {
    font-size: 13px;
    font-weight: 600;
  }
}

.inline-form {
  gap: 8px;
}

.hub-results,
.mcp-list {
  display: grid;
  max-height: 200px;
  overflow: auto;
  border: 1px solid $border-color;
  border-radius: 6px;
}

.hub-result,
.mcp-list > div {
  justify-content: space-between;
  gap: 12px;
  padding: 9px 10px;
  border-bottom: 1px solid $border-color;

  &:last-child {
    border-bottom: 0;
  }

  & > div,
  & > span {
    min-width: 0;
    flex: 1;
  }

  small {
    display: block;
    margin-top: 3px;
    color: $text-secondary;
  }
}

.selected-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;

}

.removable-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 24px;
  padding: 2px 7px;
  border: 1px solid $border-color;
  border-radius: 4px;
  background: $bg-secondary;
  font-size: 12px;

  button {
    padding: 0;
    border: 0;
    color: $text-secondary;
    background: transparent;
    cursor: pointer;
  }
}

.mcp-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.review-section dl {
  display: grid;
  gap: 0;
  margin: 0;
  border: 1px solid $border-color;
  border-radius: 6px;
  overflow: hidden;

  div {
    display: grid;
    grid-template-columns: 150px minmax(0, 1fr);
    gap: 12px;
    padding: 10px 12px;
    border-bottom: 1px solid $border-color;

    &:last-child {
      border-bottom: 0;
    }
  }

  dt {
    color: $text-secondary;
  }

  dd {
    min-width: 0;
    margin: 0;
    overflow-wrap: anywhere;
  }
}

.builder-footer {
  justify-content: space-between;
  margin-top: 16px;
}

@media (max-width: 720px) {
  .profile-builder-view {
    padding: 64px 16px 16px;
  }

  .builder-steps {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .builder-panel {
    min-height: 0;
    padding: 16px;
  }

  .model-picker,
  .mcp-fields {
    grid-template-columns: 1fr;
  }

  .review-section dl div {
    grid-template-columns: 1fr;
    gap: 4px;
  }
}
</style>
