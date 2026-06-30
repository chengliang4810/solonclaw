<script setup lang="ts">
import { computed } from 'vue'
import { Button } from 'antdv-next'
import MarkdownRenderer from '@/components/solonclaw/chat/MarkdownRenderer.vue'

type PanelDensity = 'comfortable' | 'compact'

const props = withDefaults(
  defineProps<{
    readonly modelValue: string
    readonly displayContent?: string
    readonly editing: boolean
    readonly loading?: boolean
    readonly empty: boolean
    readonly emptyText: string
    readonly placeholder: string
    readonly saving?: boolean
    readonly loadingText: string
    readonly cancelText: string
    readonly saveText: string
    readonly density?: PanelDensity
  }>(),
  {
    displayContent: undefined,
    loading: false,
    saving: false,
    density: 'comfortable',
  },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void
  (event: 'save'): void
  (event: 'cancel'): void
}>()

const draft = computed({
  get: () => props.modelValue,
  set: (value: string) => emit('update:modelValue', value),
})
const previewContent = computed(() => props.displayContent ?? props.modelValue)
const densityClass = computed(() => `is-${props.density}`)
</script>

<template>
  <div class="markdown-document-panel">
    <div v-if="loading" class="document-loading">{{ loadingText }}</div>
    <div v-else class="document-sections single">
      <div class="document-section">
        <slot name="intro" />

        <div v-if="!editing" class="document-body" :class="densityClass">
          <MarkdownRenderer v-if="!empty" :content="previewContent" />
          <p v-else class="empty-text">{{ emptyText }}</p>
        </div>

        <div v-else class="document-edit" :class="densityClass">
          <textarea
            v-model="draft"
            class="edit-textarea"
            :placeholder="placeholder"
            spellcheck="false"
          ></textarea>
          <div class="edit-actions">
            <Button size="small" @click="emit('cancel')">{{ cancelText }}</Button>
            <Button size="small" type="primary" :loading="saving" @click="emit('save')">{{ saveText }}</Button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.markdown-document-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.document-loading {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: $text-muted;
}

.document-sections {
  display: flex;
  flex: 1;
  min-height: 0;

  &.single {
    width: 100%;
  }

  @media (max-width: $breakpoint-mobile) {
    flex-direction: column;
  }
}

.document-section {
  flex: 1;
  min-height: 0;
  border: 1px solid $border-color;
  border-radius: $radius-md;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.document-body {
  flex: 1;
  overflow-y: auto;
  min-height: 0;

  &.is-compact {
    padding: 16px;
  }

  &.is-comfortable {
    padding: 20px;
  }
}

.empty-text {
  color: $text-muted;
  font-style: italic;
  font-size: 13px;
}

.document-edit {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;

  &.is-compact {
    padding: 12px 16px;
  }

  &.is-comfortable {
    padding: 20px;
  }
}

.edit-textarea {
  flex: 1;
  width: 100%;
  min-height: 0;
  padding: 12px;
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  background: $bg-input;
  color: $text-primary;
  font-family: $font-code;
  font-size: 13px;
  line-height: 1.6;
  resize: none;
  outline: none;

  &:focus {
    border-color: $accent-primary;
  }
}

.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}
</style>
