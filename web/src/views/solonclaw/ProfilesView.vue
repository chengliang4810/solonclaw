<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Button, Checkbox, Form, FormItem, Input, Modal, Select, Spin, Switch, Tag, TextArea, message } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import {
  fetchProfileModelChoices,
  type ProfileModelChoice,
  type SolonClawProfile,
} from '@/api/solonclaw/profiles'
import { useProfilesStore } from '@/stores/solonclaw/profiles'
import { copyToClipboard } from '@/utils/clipboard'

const PROFILE_NAME_RE = /^[a-z0-9][a-z0-9_-]{0,63}$/
const MODEL_KEY_SEPARATOR = '\u0000'

type EditorKind = 'description' | 'model' | 'soul' | 'alias'
type GatewayAction = 'start' | 'stop' | 'restart'

const { t } = useI18n()
const router = useRouter()
const profilesStore = useProfilesStore()

const createOpen = ref(false)
const renameOpen = ref(false)
const importOpen = ref(false)
const editorOpen = ref(false)
const distributionOpen = ref(false)
const gatewayOpen = ref(false)
const createName = ref('')
const createDescription = ref('')
const cloneFrom = ref('default')
const cloneAll = ref(false)
const noSkills = ref(false)
const createModelChoice = ref('')
const renameFrom = ref('')
const renameTo = ref('')
const importName = ref('')
const importFile = ref<File | null>(null)
const importInput = ref<HTMLInputElement | null>(null)
const exportingName = ref('')
const modelChoices = ref<ProfileModelChoice[] | null>(null)
const modelChoicesLoading = ref(false)
const editorKind = ref<EditorKind>('description')
const editorProfile = ref<SolonClawProfile | null>(null)
const editorDescription = ref('')
const editorSoul = ref('')
const editorAlias = ref('')
const editorModelChoice = ref('')
const editorLoading = ref(false)
const descriptionGenerating = ref(false)
const distributionMode = ref<'install' | 'update'>('install')
const distributionProfile = ref<SolonClawProfile | null>(null)
const distributionSource = ref('')
const distributionName = ref('')
const distributionAlias = ref(false)
const distributionForce = ref(false)
const distributionForceConfig = ref(false)
const gatewayProfile = ref<SolonClawProfile | null>(null)
const gatewayAction = ref<GatewayAction>('start')
const gatewayArgs = ref('')
const gatewayForce = ref(false)
let editorRequestId = 0

const cloneOptions = computed(() => [
  { label: t('profiles.freshProfile'), value: '' },
  ...profilesStore.profiles.map(profile => ({ label: profile.name, value: profile.name })),
])

const modelOptions = computed(() => [
  { label: t('profiles.modelInherit'), value: '' },
  ...(modelChoices.value || []).map(choice => ({ label: choice.label, value: modelKey(choice) })),
])

const surfaceRoutes = computed(() => [
  { label: t('profiles.surfaceConfig'), name: 'solonclaw.settings' },
  { label: t('profiles.surfaceSessions'), name: 'solonclaw.runs' },
  { label: t('profiles.surfaceMemory'), name: 'solonclaw.persona.journal' },
  { label: t('profiles.surfaceSkills'), name: 'solonclaw.skills' },
  { label: t('profiles.surfaceMcp'), name: 'solonclaw.mcp' },
  { label: t('profiles.surfaceChannels'), name: 'solonclaw.channels' },
  { label: t('profiles.surfaceGateway'), name: 'solonclaw.gateways' },
])

const editorTitle = computed(() => {
  const profileName = editorProfile.value?.name || ''
  const label = editorKind.value === 'description'
    ? t('profiles.description')
    : editorKind.value === 'model'
      ? t('profiles.editModel')
      : editorKind.value === 'soul'
        ? t('profiles.soulSection')
        : t('profiles.manageAliases')
  return `${label} · ${profileName}`
})

onMounted(() => {
  void profilesStore.fetchProfiles(false).catch(() => {})
})

watch(cloneFrom, source => {
  if (!source) cloneAll.value = false
})

function modelKey(choice: ProfileModelChoice): string {
  return `${choice.provider}${MODEL_KEY_SEPARATOR}${choice.model}`
}

function selectedModel(value: string): ProfileModelChoice | undefined {
  return modelChoices.value?.find(choice => modelKey(choice) === value)
}

async function loadModelChoices(): Promise<void> {
  if (modelChoices.value !== null || modelChoicesLoading.value) return
  modelChoicesLoading.value = true
  try {
    modelChoices.value = await fetchProfileModelChoices()
  } catch (error) {
    modelChoices.value = []
    message.error(error instanceof Error ? error.message : t('profiles.modelLoadFailed'))
  } finally {
    modelChoicesLoading.value = false
  }
}

function resetCreateForm(): void {
  createName.value = ''
  createDescription.value = ''
  cloneFrom.value = 'default'
  cloneAll.value = false
  noSkills.value = false
  createModelChoice.value = ''
}

function openCreate(): void {
  resetCreateForm()
  createOpen.value = true
  void loadModelChoices()
}

function openBuilder(): void {
  void router.push({ name: 'solonclaw.profiles.new' })
}

async function submitCreate(): Promise<void> {
  const name = createName.value.trim()
  if (!PROFILE_NAME_RE.test(name)) {
    message.warning(t('profiles.invalidName'))
    return
  }
  const picked = selectedModel(createModelChoice.value)
  try {
    const result = await profilesStore.createProfile({
      name,
      clone_from: cloneFrom.value || null,
      clone_all: !!cloneFrom.value && cloneAll.value,
      no_skills: !cloneFrom.value && noSkills.value,
      description: createDescription.value.trim() || undefined,
      provider: picked?.provider,
      model: picked?.model,
    })
    createOpen.value = false
    message.success(t('profiles.createSuccess', { name }))
    if (picked && result.model_set === false) message.warning(t('profiles.modelCreateWarning'))
  } catch (error) {
    message.error(error instanceof Error ? error.message : t('profiles.createFailed'))
  }
}

function openRename(profile: SolonClawProfile): void {
  renameFrom.value = profile.name
  renameTo.value = profile.name
  renameOpen.value = true
}

async function submitRename(): Promise<void> {
  const name = renameTo.value.trim()
  if (!name || name === renameFrom.value) {
    renameOpen.value = false
    return
  }
  if (!PROFILE_NAME_RE.test(name)) {
    message.warning(t('profiles.invalidName'))
    return
  }
  try {
    await profilesStore.renameProfile(renameFrom.value, name)
    renameOpen.value = false
    message.success(t('profiles.renameSuccess'))
  } catch (error) {
    message.error(error instanceof Error ? error.message : t('profiles.renameFailed'))
  }
}

function confirmSetActive(profile: SolonClawProfile): void {
  Modal.confirm({
    title: t('profiles.setActive'),
    content: t('profiles.activeConfirm', { name: profile.name }),
    okText: t('common.confirm'),
    cancelText: t('common.cancel'),
    async onOk() {
      try {
        await profilesStore.setActiveProfile(profile.name)
        message.success(t('profiles.switchSuccess', { name: profile.name }))
      } catch (error) {
        message.error(error instanceof Error ? error.message : t('profiles.switchFailed'))
        throw error
      }
    },
  })
}

function confirmDelete(profile: SolonClawProfile): void {
  const content = profile.gateway.running
    ? `${t('profiles.deleteConfirm', { name: profile.name })}\n\n${t('profiles.gatewayDeleteWarning')}`
    : t('profiles.deleteConfirm', { name: profile.name })
  Modal.confirm({
    title: t('profiles.delete'),
    content,
    okText: t('common.delete'),
    okType: 'danger',
    cancelText: t('common.cancel'),
    async onOk() {
      try {
        await profilesStore.deleteProfile(profile.name)
        message.success(t('profiles.deleteSuccess'))
      } catch (error) {
        message.error(error instanceof Error ? error.message : t('profiles.deleteFailed'))
        throw error
      }
    },
  })
}

async function exportArchive(profile: SolonClawProfile): Promise<void> {
  exportingName.value = profile.name
  try {
    await profilesStore.exportProfile(profile.name)
    message.success(t('profiles.exportSuccess'))
  } catch (error) {
    message.error(error instanceof Error ? error.message : t('profiles.exportFailed'))
  } finally {
    exportingName.value = ''
  }
}

function selectArchive(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] || null
  if (file && !/\.(tar\.gz|tgz)$/i.test(file.name)) {
    message.warning(t('profiles.importInvalidFile'))
    input.value = ''
    importFile.value = null
    return
  }
  importFile.value = file
}

function openImport(): void {
  importName.value = ''
  importFile.value = null
  if (importInput.value) importInput.value.value = ''
  importOpen.value = true
}

async function submitImport(): Promise<void> {
  if (!importFile.value) {
    message.warning(t('profiles.importSelectFile'))
    return
  }
  const name = importName.value.trim()
  if (name && !PROFILE_NAME_RE.test(name)) {
    message.warning(t('profiles.invalidName'))
    return
  }
  try {
    await profilesStore.importProfile(importFile.value, name || undefined)
    importOpen.value = false
    message.success(t('profiles.importSuccess'))
  } catch (error) {
    message.error(error instanceof Error ? error.message : t('profiles.importFailed'))
  }
}

function manageSurface(profile: SolonClawProfile, routeName: string): void {
  profilesStore.setManagementProfile(profile.name)
  void router.push({
    name: routeName,
    query: profilesStore.managementProfile ? { profile: profilesStore.managementProfile } : {},
  })
}

async function copySetupCommand(profile: SolonClawProfile): Promise<void> {
  try {
    const result = await profilesStore.fetchSetupCommand(profile.name)
    if (!await copyToClipboard(result.command)) throw new Error(t('profiles.copyCommandFailed'))
    message.success(t('profiles.commandCopied'))
  } catch (error) {
    message.error(error instanceof Error ? error.message : t('profiles.copyCommandFailed'))
  }
}

async function openTerminal(profile: SolonClawProfile): Promise<void> {
  try {
    await profilesStore.openTerminal(profile.name)
    message.success(t('profiles.terminalOpened'))
  } catch (error) {
    message.error(error instanceof Error ? error.message : t('profiles.openTerminalFailed'))
  }
}

async function openEditor(profile: SolonClawProfile, kind: EditorKind): Promise<void> {
  editorRequestId += 1
  const requestId = editorRequestId
  editorProfile.value = profile
  editorKind.value = kind
  editorDescription.value = profile.description || ''
  editorSoul.value = ''
  editorAlias.value = ''
  editorModelChoice.value = ''
  editorOpen.value = true
  if (kind === 'model') {
    await loadModelChoices()
    const exact = modelChoices.value?.find(choice =>
      choice.model === profile.model && (!profile.provider || choice.provider === profile.provider),
    )
    if (requestId === editorRequestId && exact) editorModelChoice.value = modelKey(exact)
  }
  if (kind === 'soul') {
    editorLoading.value = true
    try {
      const soul = await profilesStore.fetchSoul(profile.name)
      if (requestId === editorRequestId) editorSoul.value = soul.content || ''
    } catch (error) {
      if (requestId === editorRequestId) message.error(error instanceof Error ? error.message : t('profiles.soulLoadFailed'))
    } finally {
      if (requestId === editorRequestId) editorLoading.value = false
    }
  }
}

function closeEditor(): void {
  editorRequestId += 1
  editorOpen.value = false
  editorProfile.value = null
}

function isCurrentEditorRequest(requestId: number, profileName: string, kind: EditorKind): boolean {
  return requestId === editorRequestId
    && editorOpen.value
    && editorProfile.value?.name === profileName
    && editorKind.value === kind
}

async function saveEditor(): Promise<void> {
  const profile = editorProfile.value
  if (!profile) return
  const requestId = editorRequestId
  const profileName = profile.name
  const kind = editorKind.value
  try {
    if (kind === 'description') {
      await profilesStore.updateDescription(profileName, editorDescription.value)
      if (isCurrentEditorRequest(requestId, profileName, kind)) {
        message.success(t('profiles.descriptionSaved'))
        closeEditor()
      }
      return
    }
    if (kind === 'soul') {
      await profilesStore.updateSoul(profileName, editorSoul.value)
      if (isCurrentEditorRequest(requestId, profileName, kind)) {
        message.success(t('profiles.soulSaved'))
        closeEditor()
      }
      return
    }
    if (kind === 'model') {
      const model = selectedModel(editorModelChoice.value)
      if (!model) {
        message.warning(t('profiles.modelSelect'))
        return
      }
      await profilesStore.updateModel(profileName, model.provider, model.model)
      if (isCurrentEditorRequest(requestId, profileName, kind)) {
        message.success(t('profiles.modelSaved'))
        closeEditor()
      }
      return
    }
    await profilesStore.createAlias(profileName, editorAlias.value || undefined)
    if (isCurrentEditorRequest(requestId, profileName, kind)) {
      editorAlias.value = ''
      editorProfile.value = profilesStore.profiles.find(item => item.name === profileName) || profile
      message.success(t('profiles.aliasCreated'))
    }
  } catch (error) {
    if (isCurrentEditorRequest(requestId, profileName, kind)) {
      message.error(error instanceof Error ? error.message : t('common.saveFailed'))
    }
  }
}

async function generateDescription(): Promise<void> {
  const profile = editorProfile.value
  if (!profile || descriptionGenerating.value) return
  const requestId = editorRequestId
  const profileName = profile.name
  descriptionGenerating.value = true
  try {
    const result = await profilesStore.describeAutomatically(profileName, true)
    if (!isCurrentEditorRequest(requestId, profileName, 'description')) return
    if (!result.ok) {
      message.error(result.reason || t('profiles.describeFailed'))
      return
    }
    editorDescription.value = result.description || ''
    message.success(t('profiles.descriptionGenerated'))
  } catch (error) {
    if (isCurrentEditorRequest(requestId, profileName, 'description')) {
      message.error(error instanceof Error ? error.message : t('profiles.describeFailed'))
    }
  } finally {
    descriptionGenerating.value = false
  }
}

function confirmRemoveAlias(profile: SolonClawProfile, alias: string): void {
  const requestId = editorRequestId
  Modal.confirm({
    title: t('profiles.removeAlias'),
    content: t('profiles.removeAliasConfirm', { alias }),
    okText: t('common.remove'),
    okType: 'danger',
    cancelText: t('common.cancel'),
    async onOk() {
      await profilesStore.removeAlias(profile.name, alias)
      if (isCurrentEditorRequest(requestId, profile.name, 'alias')) {
        editorProfile.value = profilesStore.profiles.find(item => item.name === profile.name) || profile
        message.success(t('profiles.aliasRemoved'))
      }
    },
  })
}

function openInstallDistribution(): void {
  distributionMode.value = 'install'
  distributionProfile.value = null
  distributionSource.value = ''
  distributionName.value = ''
  distributionAlias.value = false
  distributionForce.value = false
  distributionForceConfig.value = false
  distributionOpen.value = true
}

function openUpdateDistribution(profile: SolonClawProfile): void {
  distributionMode.value = 'update'
  distributionProfile.value = profile
  distributionForceConfig.value = false
  distributionOpen.value = true
}

async function submitDistribution(): Promise<void> {
  try {
    if (distributionMode.value === 'install') {
      const source = distributionSource.value.trim()
      const name = distributionName.value.trim()
      if (!source) {
        message.warning(t('profiles.distributionSourceRequired'))
        return
      }
      if (name && !PROFILE_NAME_RE.test(name)) {
        message.warning(t('profiles.invalidName'))
        return
      }
      await profilesStore.installDistribution({
        source,
        name: name || undefined,
        alias: distributionAlias.value,
        force: distributionForce.value,
      })
      message.success(t('profiles.distributionInstalled'))
    } else if (distributionProfile.value) {
      await profilesStore.updateDistribution(distributionProfile.value.name, distributionForceConfig.value)
      message.success(t('profiles.distributionUpdated'))
    }
    distributionOpen.value = false
  } catch (error) {
    message.error(error instanceof Error ? error.message : t('profiles.distributionFailed'))
  }
}

function requestGateway(profile: SolonClawProfile, action: GatewayAction): void {
  if (action === 'stop') {
    Modal.confirm({
      title: t('profiles.gatewayStop'),
      content: t('profiles.gatewayStopConfirm', { name: profile.name }),
      okText: t('common.stop'),
      cancelText: t('common.cancel'),
      async onOk() {
        await profilesStore.updateGateway(profile.name, 'stop')
        message.success(t('profiles.gatewayStopped'))
      },
    })
    return
  }
  gatewayProfile.value = profile
  gatewayAction.value = action
  gatewayArgs.value = ''
  gatewayForce.value = false
  gatewayOpen.value = true
}

async function submitGateway(): Promise<void> {
  const profile = gatewayProfile.value
  if (!profile) return
  const args = gatewayArgs.value
    .split(/\r?\n/)
    .map(item => item.trim())
    .filter(Boolean)
  try {
    await profilesStore.updateGateway(profile.name, gatewayAction.value, {
      args: args.length ? args : undefined,
      force: gatewayForce.value,
    })
    gatewayOpen.value = false
    message.success(gatewayAction.value === 'start' ? t('profiles.gatewayStarted') : t('profiles.gatewayRestarted'))
  } catch (error) {
    message.error(error instanceof Error ? error.message : t('profiles.gatewayActionFailed'))
  }
}

function canRename(profile: SolonClawProfile): boolean {
  return profile.name !== 'default' && !profile.current
}

function canDelete(profile: SolonClawProfile): boolean {
  return profile.name !== 'default' && !profile.active && !profile.current
}

function hasDistribution(profile: SolonClawProfile): boolean {
  return Object.keys(profile.distribution || {}).length > 0
}

function distributionLabel(profile: SolonClawProfile): string {
  const distribution = profile.distribution || {}
  const name = String(distribution.name || profile.name)
  const version = distribution.version ? `@${String(distribution.version)}` : ''
  return `${name}${version}`
}
</script>

<template>
  <div class="profiles-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('profiles.title') }}</h2>
        <p class="header-subtitle">{{ t('profiles.subtitle') }}</p>
      </div>
      <div class="header-actions">
        <Button @click="openImport">{{ t('profiles.import') }}</Button>
        <Button @click="openInstallDistribution">{{ t('profiles.installDistribution') }}</Button>
        <Button @click="openBuilder">{{ t('profiles.build') }}</Button>
        <Button type="primary" @click="openCreate">{{ t('common.create') }}</Button>
      </div>
    </header>

    <main class="profiles-content">
      <div class="scope-summary">
        <span>{{ t('profiles.managementTarget') }}</span>
        <strong>{{ profilesStore.managedProfileName }}</strong>
        <span class="summary-divider">/</span>
        <span>{{ t('profiles.activeDefault') }}</span>
        <strong>{{ profilesStore.activeProfileName }}</strong>
        <span class="summary-divider">/</span>
        <span>{{ t('profiles.dashboardRunsAs') }}</span>
        <strong>{{ profilesStore.currentProfileName }}</strong>
      </div>

      <div v-if="profilesStore.loadError" class="load-error">
        <span>{{ profilesStore.loadError }}</span>
        <Button size="small" @click="profilesStore.fetchProfiles(false)">{{ t('common.retry') }}</Button>
      </div>

      <Spin :spinning="profilesStore.loading && profilesStore.profiles.length === 0" size="large">
        <div v-if="profilesStore.profiles.length === 0 && !profilesStore.loadError" class="empty-state">
          {{ t('profiles.noProfiles') }}
        </div>

        <div v-else class="profile-grid">
          <article
            v-for="profile in profilesStore.profiles"
            :key="profile.name"
            class="profile-card"
            :class="{ managed: profilesStore.managedProfileName === profile.name }"
          >
            <header class="profile-header">
              <div class="profile-identity">
                <h3>{{ profile.name }}</h3>
                <div class="profile-badges">
                  <Tag v-if="profile.active" color="success">{{ t('profiles.active') }}</Tag>
                  <Tag v-if="profile.name === 'default'">{{ t('profiles.default') }}</Tag>
                  <Tag v-if="profile.current" color="processing">{{ t('profiles.current') }}</Tag>
                  <Tag v-if="profile.aliases.length">{{ t('profiles.aliasBadge') }}</Tag>
                  <Tag v-if="profile.credentials_exists">{{ t('profiles.hasEnv') }}</Tag>
                  <Tag v-if="hasDistribution(profile)">{{ distributionLabel(profile) }}</Tag>
                </div>
              </div>
              <div class="profile-actions">
                <Button v-if="!profile.active" size="small" @click="confirmSetActive(profile)">{{ t('profiles.setActive') }}</Button>
                <Button
                  v-if="profile.gateway.running"
                  size="small"
                  danger
                  :disabled="profilesStore.mutating"
                  @click="requestGateway(profile, 'stop')"
                >
                  {{ t('common.stop') }}
                </Button>
                <Button
                  v-else
                  size="small"
                  :disabled="profilesStore.mutating"
                  @click="requestGateway(profile, 'start')"
                >
                  {{ t('common.start') }}
                </Button>
                <Button
                  v-if="profile.gateway.running"
                  size="small"
                  :disabled="profilesStore.mutating"
                  @click="requestGateway(profile, 'restart')"
                >
                  {{ t('common.restart') }}
                </Button>
              </div>
            </header>

            <div class="gateway-status" :class="{ running: profile.gateway.running }">
              <span class="status-dot" />
              <span>{{ profile.gateway.running ? t('profiles.gatewayRunning') : t('profiles.gatewayStopped') }}</span>
              <span v-if="profile.gateway.port">:{{ profile.gateway.port }}</span>
              <span v-if="profile.gateway.pid">PID {{ profile.gateway.pid }}</span>
            </div>

            <div class="description-row">
              <p :class="{ empty: !profile.description }">{{ profile.description || t('profiles.noDescription') }}</p>
              <Tag v-if="profile.description && profile.description_auto" color="warning">{{ t('profiles.reviewBadge') }}</Tag>
            </div>

            <dl class="profile-details">
              <div>
                <dt>{{ t('profiles.model') }}</dt>
                <dd>{{ profile.model || '-' }}<span v-if="profile.provider"> ({{ profile.provider }})</span></dd>
              </div>
              <div>
                <dt>{{ t('profiles.skills') }}</dt>
                <dd>{{ profile.skills_count }}</dd>
              </div>
              <div>
                <dt>{{ t('profiles.alias') }}</dt>
                <dd>{{ profile.aliases.join(', ') || '-' }}</dd>
              </div>
              <div class="wide">
                <dt>{{ t('profiles.path') }}</dt>
                <dd class="mono">{{ profile.home }}</dd>
              </div>
            </dl>

            <div v-if="hasDistribution(profile)" class="distribution-row">
              <span>
                <strong>{{ t('profiles.distribution') }}</strong>
                {{ distributionLabel(profile) }}
                <small v-if="profile.distribution.source">{{ profile.distribution.source }}</small>
              </span>
              <Button size="small" @click="openUpdateDistribution(profile)">{{ t('common.update') }}</Button>
            </div>

            <div class="editor-actions">
              <Button size="small" @click="openEditor(profile, 'model')">{{ t('profiles.editModel') }}</Button>
              <Button size="small" @click="openEditor(profile, 'description')">{{ t('profiles.editDescription') }}</Button>
              <Button size="small" @click="openEditor(profile, 'soul')">{{ t('profiles.editSoul') }}</Button>
              <Button size="small" @click="openEditor(profile, 'alias')">{{ t('profiles.manageAliases') }}</Button>
              <Button size="small" @click="openTerminal(profile)">{{ t('profiles.openTerminal') }}</Button>
              <Button size="small" @click="copySetupCommand(profile)">{{ t('profiles.copyCommand') }}</Button>
            </div>

            <div class="surface-links" :aria-label="t('profiles.scopedSurfaces')">
              <Button v-for="surface in surfaceRoutes" :key="surface.name" size="small" type="text" @click="manageSurface(profile, surface.name)">
                {{ surface.label }}
              </Button>
            </div>

            <footer class="card-footer">
              <Button size="small" :loading="exportingName === profile.name" @click="exportArchive(profile)">{{ t('profiles.export') }}</Button>
              <Button size="small" :disabled="!canRename(profile)" @click="openRename(profile)">{{ t('profiles.rename') }}</Button>
              <Button size="small" danger :disabled="!canDelete(profile)" @click="confirmDelete(profile)">{{ t('profiles.delete') }}</Button>
            </footer>
          </article>
        </div>
      </Spin>
    </main>

    <Modal v-model:open="createOpen" :title="t('profiles.quickCreate')" :style="{ width: 'min(560px, calc(100vw - 32px))' }">
      <Form layout="vertical">
        <FormItem :label="t('profiles.name')" required>
          <Input v-model:value="createName" :placeholder="t('profiles.namePlaceholder')" @keyup.enter="submitCreate" />
        </FormItem>
        <FormItem :label="t('profiles.cloneSource')">
          <Select v-model:value="cloneFrom" :options="cloneOptions" />
        </FormItem>
        <FormItem :label="t('profiles.descriptionOptional')">
          <TextArea v-model:value="createDescription" :autosize="{ minRows: 2, maxRows: 5 }" :placeholder="t('profiles.descriptionPlaceholder')" />
        </FormItem>
        <FormItem :label="t('profiles.modelOptional')">
          <Select
            v-model:value="createModelChoice"
            show-search
            :loading="modelChoicesLoading"
            :options="modelOptions"
            :placeholder="modelChoicesLoading ? t('profiles.modelLoading') : t('profiles.modelSelect')"
          />
        </FormItem>
        <div class="option-list">
          <label v-if="cloneFrom">
            <span>{{ t('profiles.cloneAll') }}</span>
            <Switch v-model:value="cloneAll" />
          </label>
          <label v-else>
            <span>{{ t('profiles.noSkills') }}</span>
            <Switch v-model:value="noSkills" />
          </label>
        </div>
      </Form>
      <template #footer>
        <div class="modal-footer">
          <Button @click="createOpen = false">{{ t('common.cancel') }}</Button>
          <Button type="primary" :loading="profilesStore.mutating" @click="submitCreate">{{ t('common.create') }}</Button>
        </div>
      </template>
    </Modal>

    <Modal v-model:open="renameOpen" :title="t('profiles.rename')" :style="{ width: 'min(420px, calc(100vw - 32px))' }">
      <Form layout="vertical">
        <FormItem :label="t('profiles.newName')" required>
          <Input v-model:value="renameTo" :placeholder="t('profiles.newNamePlaceholder')" @keyup.enter="submitRename" />
          <small class="field-help">{{ t('profiles.invalidName') }}</small>
        </FormItem>
      </Form>
      <template #footer>
        <div class="modal-footer">
          <Button @click="renameOpen = false">{{ t('common.cancel') }}</Button>
          <Button type="primary" :loading="profilesStore.mutating" @click="submitRename">{{ t('common.save') }}</Button>
        </div>
      </template>
    </Modal>

    <Modal v-model:open="importOpen" :title="t('profiles.import')" :style="{ width: 'min(460px, calc(100vw - 32px))' }">
      <Form layout="vertical">
        <FormItem :label="t('profiles.importSelectFile')" required>
          <input ref="importInput" class="native-file-input" type="file" accept=".tar.gz,.tgz,application/gzip" @change="selectArchive" />
          <div class="file-picker-row">
            <Button @click="importInput?.click()">{{ t('profiles.importSelectFile') }}</Button>
            <span>{{ importFile?.name || t('profiles.noArchiveSelected') }}</span>
          </div>
        </FormItem>
        <FormItem :label="t('profiles.importName')">
          <Input v-model:value="importName" :placeholder="t('profiles.importNamePlaceholder')" />
        </FormItem>
      </Form>
      <template #footer>
        <div class="modal-footer">
          <Button @click="importOpen = false">{{ t('common.cancel') }}</Button>
          <Button type="primary" :loading="profilesStore.mutating" :disabled="!importFile" @click="submitImport">{{ t('common.confirm') }}</Button>
        </div>
      </template>
    </Modal>

    <Modal v-model:open="editorOpen" :title="editorTitle" :style="{ width: 'min(620px, calc(100vw - 32px))' }" @cancel="closeEditor">
      <Spin :spinning="editorLoading">
        <div v-if="editorKind === 'description'" class="editor-form">
          <div class="editor-toolbar">
            <span>{{ t('profiles.description') }}</span>
            <Button size="small" :loading="descriptionGenerating" @click="generateDescription">{{ t('profiles.autoGenerate') }}</Button>
          </div>
          <TextArea v-model:value="editorDescription" :autosize="{ minRows: 4, maxRows: 10 }" :placeholder="t('profiles.descriptionPlaceholder')" />
        </div>
        <div v-else-if="editorKind === 'model'" class="editor-form">
          <label>{{ t('profiles.modelSelect') }}</label>
          <Select
            v-model:value="editorModelChoice"
            show-search
            :loading="modelChoicesLoading"
            :options="modelOptions.slice(1)"
            :placeholder="modelChoicesLoading ? t('profiles.modelLoading') : t('profiles.modelSelect')"
          />
          <p v-if="!modelChoicesLoading && modelChoices?.length === 0" class="field-help">{{ t('profiles.modelNone') }}</p>
        </div>
        <div v-else-if="editorKind === 'soul'" class="editor-form">
          <label>{{ t('profiles.soulSection') }}</label>
          <TextArea v-model:value="editorSoul" class="soul-editor" :autosize="{ minRows: 12, maxRows: 24 }" :placeholder="t('profiles.soulPlaceholder')" />
        </div>
        <div v-else class="editor-form">
          <div class="alias-list">
            <span v-for="alias in editorProfile?.aliases || []" :key="alias" class="removable-tag">
              {{ alias }}
              <button type="button" :aria-label="t('profiles.removeAlias')" @click="editorProfile && confirmRemoveAlias(editorProfile, alias)">&times;</button>
            </span>
            <span v-if="!editorProfile?.aliases.length" class="field-help">{{ t('profiles.noAliases') }}</span>
          </div>
          <Input v-model:value="editorAlias" :placeholder="t('profiles.aliasPlaceholder')" @keyup.enter="saveEditor" />
          <p class="field-help">{{ t('profiles.aliasHint') }}</p>
        </div>
      </Spin>
      <template #footer>
        <div class="modal-footer">
          <Button @click="closeEditor">{{ t('common.cancel') }}</Button>
          <Button type="primary" :loading="profilesStore.mutating" @click="saveEditor">
            {{ editorKind === 'alias' ? t('profiles.createAlias') : t('common.save') }}
          </Button>
        </div>
      </template>
    </Modal>

    <Modal
      v-model:open="distributionOpen"
      :title="distributionMode === 'install' ? t('profiles.installDistribution') : t('profiles.updateDistribution')"
      :style="{ width: 'min(520px, calc(100vw - 32px))' }"
    >
      <Form v-if="distributionMode === 'install'" layout="vertical">
        <FormItem :label="t('profiles.distributionSource')" required>
          <Input v-model:value="distributionSource" :placeholder="t('profiles.distributionSourcePlaceholder')" />
        </FormItem>
        <FormItem :label="t('profiles.importName')">
          <Input v-model:value="distributionName" :placeholder="t('profiles.importNamePlaceholder')" />
        </FormItem>
        <div class="checkbox-options">
          <Checkbox v-model:checked="distributionAlias">{{ t('profiles.distributionCreateAlias') }}</Checkbox>
          <Checkbox v-model:checked="distributionForce">{{ t('profiles.distributionForce') }}</Checkbox>
        </div>
      </Form>
      <div v-else class="editor-form">
        <p>{{ t('profiles.updateDistributionHint', { name: distributionProfile?.name || '' }) }}</p>
        <Checkbox v-model:checked="distributionForceConfig">{{ t('profiles.distributionForceConfig') }}</Checkbox>
      </div>
      <template #footer>
        <div class="modal-footer">
          <Button @click="distributionOpen = false">{{ t('common.cancel') }}</Button>
          <Button type="primary" :loading="profilesStore.mutating" @click="submitDistribution">
            {{ distributionMode === 'install' ? t('profiles.install') : t('common.update') }}
          </Button>
        </div>
      </template>
    </Modal>

    <Modal
      v-model:open="gatewayOpen"
      :title="gatewayAction === 'start' ? t('profiles.gatewayStart') : t('profiles.gatewayRestart')"
      :style="{ width: 'min(520px, calc(100vw - 32px))' }"
    >
      <Form layout="vertical">
        <FormItem :label="t('profiles.gatewayArgs')">
          <TextArea v-model:value="gatewayArgs" :autosize="{ minRows: 4, maxRows: 10 }" :placeholder="t('profiles.gatewayArgsPlaceholder')" />
          <small class="field-help">{{ t('profiles.gatewayArgsHint') }}</small>
        </FormItem>
        <Checkbox v-model:checked="gatewayForce">{{ t('profiles.gatewayForce') }}</Checkbox>
      </Form>
      <template #footer>
        <div class="modal-footer">
          <Button @click="gatewayOpen = false">{{ t('common.cancel') }}</Button>
          <Button type="primary" :loading="profilesStore.mutating" @click="submitGateway">
            {{ gatewayAction === 'start' ? t('common.start') : t('common.restart') }}
          </Button>
        </div>
      </template>
    </Modal>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.profiles-view {
  min-height: 100%;
  color: $text-primary;
}

.page-header,
.header-actions,
.profile-actions,
.profile-badges,
.editor-actions,
.surface-links,
.card-footer,
.modal-footer,
.file-picker-row,
.editor-toolbar,
.gateway-status,
.description-row,
.distribution-row,
.alias-list {
  display: flex;
  align-items: center;
}

.page-header {
  justify-content: space-between;
  gap: 16px;
  padding: 20px 24px;
  border-bottom: 1px solid $border-color;
}

.header-title {
  margin: 0;
  font-size: 20px;
}

.header-subtitle {
  max-width: 780px;
  margin: 4px 0 0;
  color: $text-secondary;
  font-size: 13px;
}

.header-actions,
.profile-actions,
.profile-badges,
.editor-actions,
.surface-links,
.card-footer,
.modal-footer,
.file-picker-row,
.alias-list {
  flex-wrap: wrap;
  gap: 8px;
}

.profiles-content {
  padding: 18px 24px 28px;
}

.scope-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-bottom: 14px;
  color: $text-secondary;
  font-size: 12px;

  strong {
    color: $text-primary;
  }
}

.summary-divider {
  color: $border-color;
}

.load-error,
.empty-state {
  padding: 18px;
  border: 1px solid $border-color;
  border-radius: 8px;
}

.load-error {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  border-color: rgba(var(--error-rgb), 0.3);
  color: $error;
  background: rgba(var(--error-rgb), 0.05);
}

.empty-state {
  color: $text-secondary;
  text-align: center;
}

.profile-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 360px), 1fr));
  gap: 14px;
}

.profile-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border: 1px solid $border-color;
  border-radius: 8px;
  background: $bg-primary;

  &.managed {
    border-color: rgba(var(--accent-primary-rgb), 0.55);
    box-shadow: inset 3px 0 0 $accent-primary;
  }
}

.profile-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.profile-identity {
  min-width: 0;

  h3 {
    margin: 0 0 6px;
    overflow: hidden;
    font-size: 15px;
    text-overflow: ellipsis;
  }
}

.gateway-status {
  gap: 6px;
  color: $text-secondary;
  font-size: 12px;

  &.running {
    color: $success;
  }
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: $text-muted;
}

.gateway-status.running .status-dot {
  background: $success;
}

.description-row {
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;

  p {
    display: -webkit-box;
    min-height: 36px;
    margin: 0;
    overflow: hidden;
    color: $text-secondary;
    font-size: 13px;
    line-height: 18px;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;

    &.empty {
      color: $text-muted;
      font-style: italic;
    }
  }
}

.profile-details {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin: 0;

  div {
    min-width: 0;
  }

  .wide {
    grid-column: 1 / -1;
  }

  dt {
    margin-bottom: 2px;
    color: $text-muted;
    font-size: 11px;
  }

  dd {
    margin: 0;
    overflow: hidden;
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.mono {
  font-family: $font-code;
}

.distribution-row {
  justify-content: space-between;
  gap: 10px;
  padding: 9px 10px;
  border-left: 3px solid $accent-primary;
  background: rgba(var(--accent-primary-rgb), 0.04);
  font-size: 12px;

  span {
    min-width: 0;
  }

  strong,
  small {
    display: block;
  }

  small {
    overflow: hidden;
    color: $text-muted;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.editor-actions,
.surface-links {
  padding-top: 10px;
  border-top: 1px solid $border-color;
}

.surface-links {
  gap: 2px;
}

.card-footer {
  justify-content: flex-end;
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px solid $border-color;
}

.option-list,
.checkbox-options,
.editor-form {
  display: grid;
  gap: 12px;
}

.option-list label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  font-size: 13px;
}

.native-file-input {
  display: none;
}

.file-picker-row span {
  min-width: 0;
  overflow: hidden;
  color: $text-secondary;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.modal-footer {
  justify-content: flex-end;
}

.field-help {
  display: block;
  margin-top: 5px;
  color: $text-muted;
  font-size: 11px;
}

.editor-toolbar,
.distribution-row {
  justify-content: space-between;
}

.editor-toolbar span,
.editor-form > label {
  font-size: 13px;
  font-weight: 600;
}

.editor-form > p {
  margin: 0;
  color: $text-secondary;
  font-size: 13px;
}

.soul-editor :deep(textarea) {
  font-family: $font-code;
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

@media (max-width: 760px) {
  .page-header {
    align-items: flex-start;
    flex-direction: column;
    padding: 64px 16px 16px;
  }

  .profiles-content {
    padding: 14px 16px 22px;
  }

  .profile-grid {
    grid-template-columns: 1fr;
  }

  .profile-header {
    flex-direction: column;
  }

  .profile-details {
    grid-template-columns: 1fr;

    .wide {
      grid-column: auto;
    }
  }
}
</style>
