<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NSpin, NTag } from 'naive-ui'
import { fetchDiagnostics, type Diagnostics } from '@/api/jimuqu/diagnostics'

const diagnostics = ref<Diagnostics | null>(null)
const loading = ref(false)
const securityApprovals = computed(() => diagnostics.value?.security?.approvals || {})
const securityPolicy = computed(() => diagnostics.value?.security?.policy || {})
const securityTerminal = computed(() => diagnostics.value?.security?.terminal || {})

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

async function load() {
  loading.value = true
  try {
    diagnostics.value = await fetchDiagnostics()
  } finally {
    loading.value = false
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
}
</style>
