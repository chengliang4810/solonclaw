<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { AutoComplete, Button, Checkbox, Form, FormItem, Input, Modal, Switch, Tag, TextArea, message } from 'antdv-next'
import { useAgentsStore } from '@/stores/solonclaw/agents'
import { useChatStore } from '@/stores/solonclaw/chat'
import { useModelsStore } from '@/stores/solonclaw/models'
import type { SolonClawAgent } from '@/api/solonclaw/agents'
import {
  formatAgentListInput,
  previewAgentListInput,
  serializeAgentListInput,
} from '@/shared/agentLists'
import { formatTimestampText } from '@/shared/timeFormat'

const agentsStore = useAgentsStore()
const chatStore = useChatStore()
const modelsStore = useModelsStore()
const { t } = useI18n()

const showCreateModal = ref(false)
const createName = ref('')
const createRole = ref('')

const selectedName = computed({
  get: () => agentsStore.selectedAgentName,
  set: value => {
    agentsStore.selectedAgentName = value
  },
})

const form = reactive({
  display_name: '',
  description: '',
  role_prompt: '',
  default_model: '',
  memory: '',
  allowed_tools_text: '',
  skills_text: '',
  enabled: true,
})

const selectedAgent = computed(() => agentsStore.selectedAgent)
const isReadonly = computed(() => !!selectedAgent.value?.readonly)

const modelOptions = computed(() => {
  const seen = new Set<string>()
  const options = [{ label: t('agents.globalDefaultModel'), value: '' }]
  for (const model of modelsStore.allModels) {
    if (!model.id || seen.has(model.id)) continue
    seen.add(model.id)
    options.push({
      label: model.provider ? `${model.id} (${model.provider})` : model.id,
      value: model.id,
    })
  }
  if (form.default_model && !seen.has(form.default_model)) {
    options.push({ label: form.default_model, value: form.default_model })
  }
  return options
})

const toolOptions = computed(() => previewAgentListInput(form.allowed_tools_text).map(name => ({
  label: name,
  value: name,
})))

const skillOptions = computed(() => previewAgentListInput(form.skills_text).map(name => ({
  label: name,
  value: name,
})))

function copyAgent(agent: SolonClawAgent | null) {
  form.display_name = agent?.display_name || agent?.name || ''
  form.description = agent?.description || ''
  form.role_prompt = agent?.role_prompt || ''
  form.default_model = agent?.default_model || ''
  form.memory = agent?.memory || ''
  form.allowed_tools_text = formatAgentListInput(agent?.allowed_tools_json)
  form.skills_text = formatAgentListInput(agent?.skills_json)
  form.enabled = agent?.enabled !== false
}

async function load() {
  await Promise.all([
    agentsStore.fetchAgents(chatStore.activeSessionId),
    modelsStore.fetchProviders(),
  ])
  if (agentsStore.selectedAgentName) {
    await agentsStore.fetchAgent(agentsStore.selectedAgentName, chatStore.activeSessionId)
  }
  copyAgent(selectedAgent.value)
}

async function selectAgent(name: string) {
  agentsStore.selectedAgentName = name
  await agentsStore.fetchAgent(name, chatStore.activeSessionId)
  copyAgent(selectedAgent.value)
}

async function saveAgent() {
  const agent = selectedAgent.value
  if (!agent || agent.readonly) {
    message.warning(t('agents.readonlyNoEdit'))
    return
  }
  let allowedToolsJson = '[]'
  let skillsJson = '[]'
  try {
    allowedToolsJson = serializeAgentListInput(form.allowed_tools_text)
    skillsJson = serializeAgentListInput(form.skills_text)
  } catch {
    message.warning(t('agents.listInputInvalid'))
    return
  }
  try {
    await agentsStore.updateAgent(agent.name, {
      display_name: form.display_name,
      description: form.description,
      role_prompt: form.role_prompt,
      default_model: form.default_model,
      memory: form.memory,
      allowed_tools_json: allowedToolsJson,
      skills_json: skillsJson,
      enabled: form.enabled,
    })
    message.success(t('agents.saveSuccess'))
    await agentsStore.fetchAgents(chatStore.activeSessionId)
    await selectAgent(agent.name)
  } catch (err: any) {
    message.error(err?.message || t('agents.saveFailed'))
  }
}

async function createAgent() {
  if (!createName.value.trim()) {
    message.warning(t('agents.createNameRequired'))
    return
  }
  try {
    const created = await agentsStore.createAgent({
      name: createName.value.trim(),
      role_prompt: createRole.value.trim(),
    })
    message.success(t('agents.createSuccess', { name: created.name }))
    showCreateModal.value = false
    createName.value = ''
    createRole.value = ''
    await selectAgent(created.name)
  } catch (err: any) {
    message.error(err?.message || t('agents.createFailed'))
  }
}

function confirmDelete() {
  const agent = selectedAgent.value
  if (!agent || agent.readonly) {
    message.warning(t('agents.readonlyNoDelete'))
    return
  }
  Modal.confirm({
    title: t('agents.deleteTitle'),
    content: t('agents.deleteConfirm', { name: agent.name }),
    okText: t('common.delete'),
    cancelText: t('common.cancel'),
    onOk: async () => {
      try {
        await agentsStore.deleteAgent(agent.name)
        message.success(t('agents.deleteSuccess'))
        await agentsStore.fetchAgents(chatStore.activeSessionId)
        if (agentsStore.selectedAgentName) {
          await selectAgent(agentsStore.selectedAgentName)
        } else {
          copyAgent(null)
        }
      } catch (err: any) {
        message.error(err?.message || t('agents.deleteFailed'))
      }
    },
  })
}

async function activateSelected() {
  const agent = selectedAgent.value
  if (!agent || !chatStore.activeSessionId) {
    message.warning(t('agents.selectSessionFirst'))
    return
  }
  try {
    await agentsStore.activateAgent(agent.name, chatStore.activeSessionId)
    message.success(t('agents.activateSuccess', { name: agent.name }))
    await agentsStore.fetchAgents(chatStore.activeSessionId)
  } catch (err: any) {
    message.error(err?.message || t('agents.activateFailed'))
  }
}

watch(() => chatStore.activeSessionId, async sessionId => {
  await agentsStore.fetchAgents(sessionId)
})

watch(selectedAgent, agent => {
  copyAgent(agent)
})

onMounted(load)
</script>

<template>
  <div class="agents-view">
    <header class="page-header">
      <div>
        <h2 class="header-title">{{ t('agents.title') }}</h2>
        <p class="header-subtitle">{{ t('agents.description') }}</p>
      </div>
      <div class="header-actions">
        <Button size="small" :loading="agentsStore.loading" @click="load">{{ t('agents.refresh') }}</Button>
        <Button type="primary" size="small" @click="showCreateModal = true">{{ t('agents.create') }}</Button>
      </div>
    </header>

    <main class="agents-layout">
      <aside class="agent-list">
        <button
          v-for="agent in agentsStore.agents"
          :key="agent.name"
          class="agent-row"
          :class="{ selected: agent.name === selectedName, active: agent.active }"
          @click="selectAgent(agent.name)"
        >
          <span class="agent-row-title">
            <strong>{{ agent.display_name || agent.name }}</strong>
            <Tag v-if="agent.default_agent" size="small" :bordered="false">{{ t('agents.builtin') }}</Tag>
            <Tag v-if="agent.active" size="small" color="success" :bordered="false">{{ t('agents.current') }}</Tag>
          </span>
          <span class="agent-row-name">{{ agent.name }}</span>
          <span class="agent-row-meta">{{ agent.default_model || t('agents.globalDefaultModel') }}</span>
        </button>
      </aside>

      <section class="agent-editor">
        <div v-if="!selectedAgent" class="empty">{{ t('agents.empty') }}</div>
        <template v-else>
          <div class="editor-head">
            <div>
              <h3>{{ selectedAgent.display_name || selectedAgent.name }}</h3>
              <p>{{ selectedAgent.readonly ? t('agents.readonlyHint') : t('agents.editableHint') }}</p>
            </div>
            <div class="editor-actions">
              <Button size="small" :disabled="selectedAgent.active || !selectedAgent.enabled" :loading="agentsStore.activating" @click="activateSelected">
                {{ t('agents.activate') }}
              </Button>
              <Button size="small" danger type="default" :disabled="isReadonly" @click="confirmDelete">{{ t('common.delete') }}</Button>
              <Button type="primary" size="small" :disabled="isReadonly" :loading="agentsStore.saving" @click="saveAgent">{{ t('common.save') }}</Button>
            </div>
          </div>

          <Form layout="vertical" class="agent-form">
            <div class="form-grid">
              <FormItem :label="t('agents.displayName')">
                <Input v-model:value="form.display_name" :disabled="isReadonly" :placeholder="t('agents.displayNamePlaceholder')" />
              </FormItem>
              <FormItem :label="t('agents.defaultModel')">
                <AutoComplete
                  v-model:value="form.default_model"
                  :options="modelOptions"
                  :disabled="isReadonly"
                  :placeholder="t('agents.globalDefaultModel')"
                />
              </FormItem>
            </div>

            <FormItem :label="t('agents.descriptionLabel')">
              <Input v-model:value="form.description" :disabled="isReadonly" :placeholder="t('agents.descriptionPlaceholder')" />
            </FormItem>

            <FormItem :label="t('agents.rolePrompt')">
              <TextArea
                v-model:value="form.role_prompt"
                :autosize="{ minRows: 7, maxRows: 14 }"
                :disabled="isReadonly"
                :placeholder="t('agents.rolePromptPlaceholder')"
              />
            </FormItem>

            <div class="form-grid">
              <FormItem :label="t('agents.allowedTools')">
                <TextArea
                  v-model:value="form.allowed_tools_text"
                  :autosize="{ minRows: 4, maxRows: 8 }"
                  :disabled="isReadonly"
                  :placeholder="t('agents.allowedToolsPlaceholder')"
                />
                <div v-if="toolOptions.length" class="chips">
                  <Tag v-for="tool in toolOptions" :key="tool.value" size="small" :bordered="false">{{ tool.label }}</Tag>
                </div>
              </FormItem>
              <FormItem :label="t('agents.skills')">
                <TextArea
                  v-model:value="form.skills_text"
                  :autosize="{ minRows: 4, maxRows: 8 }"
                  :disabled="isReadonly"
                  :placeholder="t('agents.skillsPlaceholder')"
                />
                <div v-if="skillOptions.length" class="chips">
                  <Tag v-for="skill in skillOptions" :key="skill.value" size="small" :bordered="false">{{ skill.label }}</Tag>
                </div>
              </FormItem>
            </div>

            <FormItem :label="t('agents.memory')">
              <TextArea
                v-model:value="form.memory"
                :autosize="{ minRows: 5, maxRows: 10 }"
                :disabled="isReadonly"
                :placeholder="t('agents.memoryPlaceholder')"
              />
            </FormItem>

            <FormItem v-if="!isReadonly" :label="t('agents.enabled')">
              <Switch v-model:value="form.enabled" />
            </FormItem>
            <Checkbox v-else :checked="true" disabled>{{ t('agents.builtinAlwaysEnabled') }}</Checkbox>
          </Form>
        </template>
      </section>

      <aside class="agent-status">
        <h3>{{ t('agents.statusTitle') }}</h3>
        <div class="status-row">
          <span>{{ t('agents.currentSession') }}</span>
          <strong>{{ agentsStore.activeAgentName || t('agents.defaultAgentName') }}</strong>
        </div>
        <div class="status-row">
          <span>{{ t('agents.runningRuns') }}</span>
          <strong>{{ selectedAgent?.running_runs || 0 }}</strong>
        </div>
        <div class="status-row">
          <span>{{ t('agents.lastUsedAt') }}</span>
          <strong>{{ formatTimestampText(selectedAgent?.last_used_at, 'zh-CN') }}</strong>
        </div>
        <div class="status-row">
          <span>{{ t('agents.updatedAt') }}</span>
          <strong>{{ formatTimestampText(selectedAgent?.updated_at, 'zh-CN') }}</strong>
        </div>
        <div class="status-row path-row">
          <span>{{ t('agents.workspacePath') }}</span>
          <code>{{ selectedAgent?.workspace_path || '-' }}</code>
        </div>
        <div class="status-row path-row">
          <span>{{ t('agents.skillsPath') }}</span>
          <code>{{ selectedAgent?.skills_path || '-' }}</code>
        </div>
        <div class="status-row path-row">
          <span>{{ t('agents.cachePath') }}</span>
          <code>{{ selectedAgent?.cache_path || '-' }}</code>
        </div>

        <h3>{{ t('agents.recentRuns') }}</h3>
        <div v-if="!selectedAgent?.recent_runs?.length" class="empty small">{{ t('agents.noRecentRuns') }}</div>
        <div v-for="run in selectedAgent?.recent_runs || []" :key="run.run_id" class="run-row">
          <span class="run-status">{{ run.status }}</span>
          <span>{{ run.model || '-' }}</span>
          <small>{{ formatTimestampText(run.started_at, 'zh-CN') }}</small>
        </div>
      </aside>
    </main>

    <Modal
      v-model:open="showCreateModal"

      :title="t('agents.createTitle')"
      :style="{ width: 'min(520px, calc(100vw - 32px))' }"
    >
      <Form layout="vertical">
        <FormItem :label="t('agents.name')" required>
          <Input v-model:value="createName" :placeholder="t('agents.namePlaceholder')" />
        </FormItem>
        <FormItem :label="t('agents.rolePrompt')">
          <TextArea
            v-model:value="createRole"
            :autosize="{ minRows: 4, maxRows: 8 }"
            :placeholder="t('agents.rolePromptOptionalPlaceholder')"
          />
        </FormItem>
      </Form>
      <template #footer>
        <div class="modal-footer">
          <Button @click="showCreateModal = false">{{ t('common.cancel') }}</Button>
          <Button type="primary" :loading="agentsStore.saving" @click="createAgent">{{ t('common.create') }}</Button>
        </div>
      </template>
    </Modal>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.agents-view {
  height: calc(100 * var(--vh));
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.header-subtitle {
  margin: 4px 0 0;
  color: $text-muted;
  font-size: 13px;
}

.header-actions,
.editor-actions,
.modal-footer {
  display: flex;
  align-items: center;
  gap: 8px;
}

.agents-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 260px minmax(420px, 1fr) 300px;
  gap: 14px;
  padding: 20px;
  overflow: hidden;
}

.agent-list,
.agent-editor,
.agent-status {
  min-height: 0;
  overflow: auto;
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  background: $bg-primary;
}

.agent-list {
  padding: 8px;
}

.agent-row {
  width: 100%;
  display: grid;
  gap: 4px;
  padding: 11px 10px;
  border: 1px solid transparent;
  border-radius: $radius-sm;
  background: transparent;
  color: $text-primary;
  text-align: left;
  cursor: pointer;
  margin-bottom: 4px;

  &:hover {
    background: rgba(var(--accent-primary-rgb), 0.05);
  }

  &.selected {
    border-color: $accent-primary;
    background: rgba(var(--accent-primary-rgb), 0.08);
  }
}

.agent-row-title {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;

  strong {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.agent-row-name,
.agent-row-meta {
  font-size: 12px;
  color: $text-muted;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-editor {
  padding: 16px;
}

.editor-head {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 16px;

  h3 {
    margin: 0 0 4px;
    font-size: 18px;
  }

  p {
    margin: 0;
    color: $text-muted;
    font-size: 13px;
  }
}

.agent-form {
  max-width: 920px;
}

.form-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.agent-status {
  padding: 14px;

  h3 {
    margin: 0 0 12px;
    font-size: 14px;
  }

  h3:not(:first-child) {
    margin-top: 18px;
  }
}

.status-row {
  display: grid;
  gap: 4px;
  padding: 9px 0;
  border-bottom: 1px solid rgba(var(--text-muted-rgb), 0.12);

  span {
    color: $text-muted;
    font-size: 12px;
  }
}

.path-row code {
  display: block;
  color: $text-secondary;
  font-family: $font-code;
  font-size: 11px;
  white-space: normal;
  overflow-wrap: anywhere;
}

.run-row {
  display: grid;
  gap: 4px;
  padding: 10px;
  margin-bottom: 8px;
  border: 1px solid rgba(var(--text-muted-rgb), 0.18);
  border-radius: $radius-sm;

  small {
    color: $text-muted;
  }
}

.run-status {
  font-family: $font-code;
  font-size: 12px;
  color: $accent-primary;
}

.empty {
  color: $text-muted;
  padding: 28px 0;
  text-align: center;
}

.empty.small {
  padding: 12px 0;
  font-size: 13px;
}

@media (max-width: 1180px) {
  .agents-layout {
    grid-template-columns: 220px minmax(0, 1fr);
  }

  .agent-status {
    grid-column: 1 / -1;
  }
}

@media (max-width: $breakpoint-mobile) {
  .agents-layout {
    grid-template-columns: 1fr;
    overflow: auto;
  }

  .editor-head,
  .form-grid {
    grid-template-columns: 1fr;
    flex-direction: column;
  }

  .editor-actions {
    flex-wrap: wrap;
  }
}
</style>
