<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NInput, NSelect, NSpin, NSwitch, NTag } from 'naive-ui'
import {
  auditSecurity,
  fetchDiagnostics,
  type Diagnostics,
  type SecurityAuditFinding,
  type SecurityAuditResult,
} from '@/api/jimuqu/diagnostics'

const diagnostics = ref<Diagnostics | null>(null)
const loading = ref(false)
const auditLoading = ref(false)
const auditResult = ref<SecurityAuditResult | null>(null)
const auditForm = ref({
  action: 'command',
  toolName: 'execute_shell',
  command: '',
  url: '',
  path: '',
  writeLike: false,
  argsJson: '',
})
const securityApprovals = computed(() => diagnostics.value?.security?.approvals || {})
const securityPolicy = computed(() => diagnostics.value?.security?.policy || {})
const securityTerminal = computed(() => diagnostics.value?.security?.terminal || {})
const auditActionOptions = [
  { label: '命令', value: 'command' },
  { label: 'URL', value: 'url' },
  { label: '路径', value: 'path' },
  { label: '工具参数', value: 'tool_args' },
]
const auditFindings = computed<SecurityAuditFinding[]>(() => auditResult.value?.findings || [])

function valueOf(source: Record<string, unknown>, key: string, fallback: unknown = '-') {
  const value = source[key]
  if (value === undefined || value === null || value === '') return fallback
  return value
}

function booleanTagType(value: unknown, goodWhenTrue = true) {
  const enabled = value === true
  return enabled === goodWhenTrue ? 'success' : 'warning'
}

function booleanText(value: unknown) {
  return value === true ? '是' : '否'
}

function decisionType(decision: unknown) {
  if (decision === 'allow') return 'success'
  if (decision === 'warn') return 'warning'
  if (decision === 'block') return 'error'
  return 'default'
}

async function load() {
  loading.value = true
  try {
    diagnostics.value = await fetchDiagnostics()
  } finally {
    loading.value = false
  }
}

async function runAudit() {
  auditLoading.value = true
  try {
    auditResult.value = await auditSecurity({
      action: auditForm.value.action,
      toolName: auditForm.value.toolName,
      command: auditForm.value.command,
      url: auditForm.value.url,
      path: auditForm.value.path,
      writeLike: auditForm.value.writeLike,
      argsJson: auditForm.value.argsJson,
    })
  } finally {
    auditLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="diagnostics-view">
    <header class="page-header">
      <h2 class="header-title">诊断</h2>
      <NButton size="small" :loading="loading" @click="load">刷新</NButton>
    </header>
    <NSpin :show="loading">
      <main class="diagnostics-grid">
        <section class="panel">
          <h3>运行目录</h3>
          <pre>{{ diagnostics?.runtime }}</pre>
        </section>
        <section class="panel">
          <h3>模型提供方</h3>
          <pre>{{ diagnostics?.providers }}</pre>
        </section>
        <section class="panel">
          <h3>渠道</h3>
          <pre>{{ diagnostics?.channels }}</pre>
        </section>
        <section class="panel">
          <h3>工具与 MCP</h3>
          <pre>{{ diagnostics?.tools }}&#10;{{ diagnostics?.mcp }}</pre>
        </section>
        <section class="panel security-panel">
          <h3>安全策略</h3>
          <div class="security-groups">
            <div class="security-group">
              <h4>审批</h4>
              <dl>
                <div>
                  <dt>命令审批</dt>
                  <dd>{{ valueOf(securityApprovals, 'mode') }}</dd>
                </div>
                <div>
                  <dt>定时任务审批</dt>
                  <dd>{{ valueOf(securityApprovals, 'cron_mode') }}</dd>
                </div>
                <div>
                  <dt>MCP 重载确认</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityApprovals.mcp_reload_confirm)">
                      {{ booleanText(securityApprovals.mcp_reload_confirm) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>长期授权</dt>
                  <dd>{{ valueOf(securityApprovals, 'always_approval_count', 0) }}</dd>
                </div>
              </dl>
            </div>
            <div class="security-group">
              <h4>URL 与网站策略</h4>
              <dl>
                <div>
                  <dt>允许私有地址</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityPolicy.allow_private_urls, false)">
                      {{ booleanText(securityPolicy.allow_private_urls) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>网站阻断表</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityPolicy.website_blocklist_enabled)">
                      {{ booleanText(securityPolicy.website_blocklist_enabled) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>域名规则</dt>
                  <dd>{{ valueOf(securityPolicy, 'website_blocklist_domain_count', 0) }}</dd>
                </div>
                <div>
                  <dt>共享规则文件</dt>
                  <dd>{{ valueOf(securityPolicy, 'website_blocklist_shared_file_count', 0) }}</dd>
                </div>
              </dl>
            </div>
            <div class="security-group">
              <h4>终端守卫</h4>
              <dl>
                <div>
                  <dt>凭据文件</dt>
                  <dd>{{ valueOf(securityTerminal, 'credential_file_count', 0) }}</dd>
                </div>
                <div>
                  <dt>环境透传</dt>
                  <dd>{{ valueOf(securityTerminal, 'env_passthrough_count', 0) }}</dd>
                </div>
                <div>
                  <dt>sudo 密码</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityTerminal.sudo_password_configured)">
                      {{ booleanText(securityTerminal.sudo_password_configured) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>写入安全根</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityTerminal.write_safe_root_configured)">
                      {{ booleanText(securityTerminal.write_safe_root_configured) }}
                    </NTag>
                  </dd>
                </div>
              </dl>
            </div>
            <div class="security-group">
              <h4>内容扫描</h4>
              <dl>
                <div>
                  <dt>扫描启用</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityPolicy.tirith_enabled)">
                      {{ booleanText(securityPolicy.tirith_enabled) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>扫描器配置</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityPolicy.tirith_configured)">
                      {{ booleanText(securityPolicy.tirith_configured) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>失败放行</dt>
                  <dd>
                    <NTag size="small" :type="booleanTagType(securityPolicy.tirith_fail_open, false)">
                      {{ booleanText(securityPolicy.tirith_fail_open) }}
                    </NTag>
                  </dd>
                </div>
                <div>
                  <dt>超时</dt>
                  <dd>{{ valueOf(securityPolicy, 'tirith_timeout_seconds', 0) }} 秒</dd>
                </div>
              </dl>
            </div>
          </div>
        </section>
        <section class="panel audit-panel">
          <h3>安全审计</h3>
          <div class="audit-layout">
            <div class="audit-form">
              <label>
                <span>类型</span>
                <NSelect v-model:value="auditForm.action" :options="auditActionOptions" size="small" />
              </label>
              <label v-if="auditForm.action === 'command' || auditForm.action === 'tool_args'">
                <span>工具名</span>
                <NInput v-model:value="auditForm.toolName" size="small" placeholder="execute_shell" />
              </label>
              <label v-if="auditForm.action === 'command'">
                <span>命令</span>
                <NInput
                  v-model:value="auditForm.command"
                  type="textarea"
                  :autosize="{ minRows: 3, maxRows: 8 }"
                  placeholder="输入待审计命令"
                />
              </label>
              <label v-if="auditForm.action === 'url'">
                <span>URL</span>
                <NInput v-model:value="auditForm.url" size="small" placeholder="https://example.com" />
              </label>
              <label v-if="auditForm.action === 'path'">
                <span>路径</span>
                <NInput v-model:value="auditForm.path" size="small" placeholder="runtime/config.yml" />
              </label>
              <label v-if="auditForm.action === 'path'" class="switch-row">
                <span>按写入检查</span>
                <NSwitch v-model:value="auditForm.writeLike" size="small" />
              </label>
              <label v-if="auditForm.action === 'tool_args'">
                <span>参数 JSON</span>
                <NInput
                  v-model:value="auditForm.argsJson"
                  type="textarea"
                  :autosize="{ minRows: 3, maxRows: 8 }"
                  placeholder="{&quot;url&quot;:&quot;https://example.com&quot;}"
                />
              </label>
              <NButton size="small" type="primary" :loading="auditLoading" @click="runAudit">审计</NButton>
            </div>
            <div class="audit-result">
              <div class="audit-summary">
                <NTag size="small" :type="decisionType(auditResult?.decision)">
                  {{ auditResult?.decision || '未审计' }}
                </NTag>
                <span>{{ auditResult?.summary || '等待输入待审计内容' }}</span>
              </div>
              <div v-if="auditFindings.length" class="finding-list">
                <div v-for="(finding, index) in auditFindings" :key="index" class="finding-item">
                  <div class="finding-meta">
                    <NTag size="small" :bordered="false">{{ finding.source || 'policy' }}</NTag>
                    <span>{{ finding.ruleId || '-' }}</span>
                    <span>{{ finding.severity || '-' }}</span>
                  </div>
                  <p>{{ finding.message }}</p>
                </div>
              </div>
              <pre v-else>{{ auditResult }}</pre>
            </div>
          </div>
        </section>
      </main>
    </NSpin>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

.diagnostics-view {
  height: calc(100 * var(--vh));
  display: flex;
  flex-direction: column;
}

.diagnostics-grid {
  padding: 20px;
  display: grid;
  grid-template-columns: repeat(2, minmax(280px, 1fr));
  gap: 16px;
}

.panel {
  border: 1px solid $border-color;
  border-radius: $radius-sm;
  background: $bg-primary;
  padding: 14px;
  min-height: 220px;
}

.security-panel {
  grid-column: 1 / -1;
}

.audit-panel {
  grid-column: 1 / -1;
}

.security-groups {
  display: grid;
  grid-template-columns: repeat(4, minmax(180px, 1fr));
  gap: 12px;
}

.security-group {
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  padding: 12px;
  background: $bg-secondary;
}

h3 {
  margin: 0 0 12px;
  font-size: 14px;
}

h4 {
  margin: 0 0 10px;
  font-size: 13px;
  color: $text-primary;
}

dl {
  margin: 0;
  display: grid;
  gap: 8px;
}

dl > div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  min-height: 24px;
}

dt,
dd {
  margin: 0;
  font-size: 12px;
}

dt {
  color: $text-muted;
}

dd {
  color: $text-primary;
  text-align: right;
}

.audit-layout {
  display: grid;
  grid-template-columns: minmax(280px, 360px) 1fr;
  gap: 16px;
}

.audit-form {
  display: grid;
  gap: 12px;
}

.audit-form label {
  display: grid;
  gap: 6px;
  font-size: 12px;
  color: $text-muted;
}

.switch-row {
  grid-template-columns: 1fr auto;
  align-items: center;
}

.audit-result {
  min-height: 220px;
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  background: $bg-secondary;
  padding: 12px;
}

.audit-summary {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
  font-size: 12px;
  color: $text-secondary;
}

.finding-list {
  display: grid;
  gap: 10px;
}

.finding-item {
  border: 1px solid $border-light;
  border-radius: $radius-sm;
  background: $bg-primary;
  padding: 10px;
}

.finding-meta {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
  font-size: 12px;
  color: $text-muted;
}

.finding-item p {
  margin: 0;
  font-size: 12px;
  color: $text-primary;
  word-break: break-word;
}

pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: $font-code;
  font-size: 12px;
  color: $text-secondary;
}

@media (max-width: 900px) {
  .diagnostics-grid {
    grid-template-columns: 1fr;
  }

  .security-groups {
    grid-template-columns: 1fr;
  }

  .audit-layout {
    grid-template-columns: 1fr;
  }
}
</style>
