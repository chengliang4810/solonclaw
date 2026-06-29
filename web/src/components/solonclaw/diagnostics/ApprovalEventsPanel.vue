<script setup lang="ts">
import { computed } from 'vue'
import { Button, Spin, Tag } from 'antdv-next'
import { useI18n } from 'vue-i18n'
import type { ApprovalEventsResult, ApprovalRuntimeEvent } from '@/api/solonclaw/diagnostics'

const props = defineProps<{
  loading: boolean
  result: ApprovalEventsResult | null
}>()

const emit = defineEmits<{
  refresh: []
}>()

const { t } = useI18n()
const events = computed<readonly ApprovalRuntimeEvent[]>(() => props.result?.events || [])

function timeText(value?: number) {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}

function decisionType(decision?: string) {
  if (decision === 'allow' || decision === 'approved') return 'success'
  if (decision === 'block' || decision === 'denied') return 'error'
  return 'warning'
}

function detailsText(details?: Record<string, unknown>) {
  return details ? JSON.stringify(details, null, 2) : ''
}
</script>

<template>
  <section class="panel approvals-panel">
    <div class="panel-title-row">
      <h3>{{ t('diagnostics.approvalEvents') }}</h3>
      <div class="panel-actions">
        <Tag size="small">{{ result?.count || events.length }}</Tag>
        <Button size="small" :loading="loading" @click="emit('refresh')">{{ t('diagnostics.refresh') }}</Button>
      </div>
    </div>
    <Spin :spinning="loading">
      <div v-if="events.length" class="approval-list">
        <article v-for="(item, index) in events" :key="`${item.timestamp || index}:${item.toolName || '-'}`" class="approval-item">
          <div class="approval-head">
            <div>
              <strong>{{ item.summary || item.toolName || '-' }}</strong>
              <span>{{ item.sourceKey || '-' }} · {{ timeText(item.timestamp) }}</span>
            </div>
            <Tag size="small" :color="decisionType(item.decision)" :bordered="false">
              {{ item.decision || '-' }}
            </Tag>
          </div>
          <pre v-if="item.details">{{ detailsText(item.details) }}</pre>
        </article>
      </div>
      <div v-else class="empty-state">{{ t('diagnostics.noApprovalEvents') }}</div>
    </Spin>
  </section>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.panel {
  grid-column: 1 / -1;
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  background: $bg-primary;
  padding: 14px;
  min-height: 220px;
}

.panel-title-row,
.approval-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}

.panel-title-row {
  margin-bottom: 12px;
}

.panel-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

h3 {
  margin: 0;
  font-size: 14px;
}

.approval-list {
  display: grid;
  gap: 12px;
}

.approval-item {
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  background: $bg-secondary;
  padding: 12px;
}

.approval-head strong {
  display: block;
  font-size: 13px;
  color: $text-primary;
}

.approval-head span {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: $text-muted;
}

.empty-state {
  min-height: 120px;
  display: grid;
  place-items: center;
  color: $text-muted;
  font-size: 13px;
}

pre {
  margin: 10px 0 0;
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  background: $bg-primary;
  padding: 10px;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: $font-code;
  font-size: 12px;
  color: $text-secondary;
}
</style>
