# Upstream Alignment Stable Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在当前 AGENTS.md 范围内补齐 solon-claw 与上游参考项目近两个版本的主要功能差距，形成可完整验收的稳定版本。

**Architecture:** 先补基础设施，再补产品面能力。基础设施包括 prompt cache/usage、SQLite canonical state、统一 command registry、tool executor/approval/file safety；产品面包括 terminal lifecycle、Skills/MCP/ACP、gateway context/platform toolsets、Dashboard/TUI 验收入口。所有裁剪项（海外渠道、多模态、browser 内置自动化、完整插件系统、复杂执行后端、价格计算等）不进入实现。

**Tech Stack:** Java 8、Solon 3.10.4、Solon AI、Snack4、Hutool、SQLite、JUnit 5、Vue 3/Vite/TypeScript、React TUI。

---

## 范围边界

### 必须实现或验收

- Prompt cache 与 cache token usage 统计。
- SQLite canonical state 补强与 session search 三模式。
- 统一 slash command registry。
- tool executor、approval、file safety 基础增强。
- terminal/process lifecycle 与 home channel 通知。
- Skills bundles / external dirs / curator 行为补齐。
- MCP / ACP 高级能力验收与缺口补齐。
- Gateway context isolation、platform toolsets、国内渠道菜单/审批键盘。
- Dashboard/TUI/API 事件和诊断入口。

### 明确不做

- 海外渠道：Telegram、Discord、Slack、WhatsApp、Signal、Matrix、Mattermost、Teams、LINE、SimpleX、Google Chat 等。
- `sms`、`webhook`。
- 多模态模型输入、图像理解/生成、video、computer use。
- TTS/STT/voice mode。
- browser 自动化内置实现。
- OpenRouter、Kimi、MiniMax、HuggingFace、Copilot、Nous、Vercel AI Gateway 等未确认 provider。
- 完整插件系统。
- Docker 之外复杂执行后端。
- OpenAI-compatible API Server。
- Profiles、多实例、多租户。
- trajectory / batch / RL / 训练辅助。

---

## 文件结构与职责

### 后端基础设施

- `src/main/java/com/jimuqu/solon/claw/llm/`
  - 补 prompt cache 请求元数据、cache usage 归集、provider transport 语义。
- `src/main/java/com/jimuqu/solon/claw/support/ModelMetadataService.java`
  - 补 provider prefix、context tiers、metadata cache。
- `src/main/java/com/jimuqu/solon/claw/storage/repository/`
  - 补 SQLite schema、session metadata、message ids、usage fields、FTS/search 支撑。
- `src/main/java/com/jimuqu/solon/claw/engine/`
  - 补 system prompt restore、compression 细节、session search mode 支撑。
- `src/main/java/com/jimuqu/solon/claw/gateway/command/`
  - 抽统一 command registry，CLI/Gateway/TUI 共享命令定义。
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/`
  - 补 tool executor 行为、clarify、file safety、approval isolation、process lifecycle。

### 后端产品能力

- `src/main/java/com/jimuqu/solon/claw/context/`
  - 补 Skills bundles、external dirs、curator/nudge 行为。
- `src/main/java/com/jimuqu/solon/claw/mcp/`
  - 补 MCP SSE/OAuth/image/sampling/keepalive 能力或明确配置级裁剪。
- `src/main/java/com/jimuqu/solon/claw/cli/acp/`
  - 补 ACP permissions/edit approval/events/images/queue/steer/Zed registry metadata。
- `src/main/java/com/jimuqu/solon/claw/gateway/`
  - 补 context isolation、platform toolsets、message canonicalization、QQBot approval keyboard、home channel notifications。
- `src/main/java/com/jimuqu/solon/claw/web/`
  - 补 Dashboard API：insights、approval events、doctor、MCP/ACP状态、platform toolsets。

### 前端

- `web/src/api/`
  - 补新增 Dashboard API 客户端类型。
- `web/src/views/jimuqu/`
  - 补 Usage/Insights、MCP、Channels、TUI、Runs、Diagnostics 的展示和控制入口。
- `web/src/tui/`
  - 补 approval events、process lifecycle、command controls、clickable URLs 等运行态投影。

### 测试

- `src/test/java/com/jimuqu/solon/claw/`
  - 每个开发包至少新增/更新相关单元测试或集成测试。
- `web/`
  - 前端改动至少执行 `npm run build`。

---

## 开发包矩阵

| 包 | 名称 | 目标 | 主要验收 |
| --- | --- | --- | --- |
| A | Prompt cache + usage | 让 provider 请求可携带 cache 语义，并记录 cache/reasoning token | LLM gateway tests 通过，Usage API 展示新增字段 |
| B | SQLite canonical state + search | 补 session metadata/lineage/message id/FTS 三模式 | session search discovery/scroll/browse 测试通过 |
| C | Command registry | CLI/Gateway/TUI 共享命令定义 | command registry 测试覆盖 alias/category/scope |
| D | Tool executor + approval + file safety | 补并发/有序结果/guardrail/hardline/file safety | tool/approval/file safety 测试通过 |
| E | Terminal lifecycle + notifications | 补后台进程 lifecycle 与通知 | process lifecycle 和 home channel 通知测试通过 |
| F | Skills bundles + curator | 补 bundles/external dirs/reload/nudge | Skill hub/curator 测试通过 |
| G | MCP / ACP | 补高级协议能力与 registry | MCP/ACP 测试通过 |
| H | Gateway context + platform toolsets | 补上下文隔离、平台工具权限、消息 canonical | gateway 并发隔离和平台权限测试通过 |
| I | Dashboard/TUI/doctor | 补验收入口与可视化 | 后端测试 + `web npm run build` 通过 |
| J | 全量验收 | 跑相关测试，修补文档配置 | Maven tests/compile 与前端 build 通过 |

---

## Task 1: Prompt cache + usage token 统计

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/llm/SolonAiLlmGateway.java`
- Modify/Create: `src/main/java/com/jimuqu/solon/claw/llm/PromptCachePolicy.java`
- Modify/Create: `src/main/java/com/jimuqu/solon/claw/llm/LlmUsage.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/support/ModelMetadataService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/config/AppConfig.java`
- Modify: `src/main/resources/app.yml`
- Modify: `config.example.yml`
- Test: `src/test/java/com/jimuqu/solon/claw/SolonAiLlmGatewayUsageTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/SolonAiLlmGatewayConfigTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/ModelMetadataServiceTest.java`

- [ ] **Step 1: 写 prompt cache 配置失败测试**

在 `SolonAiLlmGatewayConfigTest` 增加断言：配置可以读取：

```java
@Test
void shouldLoadPromptCacheConfig() {
    Properties props = new Properties();
    props.setProperty("solonclaw.llm.promptCache.enabled", "true");
    props.setProperty("solonclaw.llm.promptCache.ttl", "1h");
    props.setProperty("solonclaw.llm.promptCache.layout", "system_and_3");

    AppConfig config = AppConfig.load(new org.noear.solon.core.Props(props));

    assertThat(config.getLlm().getPromptCache().isEnabled()).isTrue();
    assertThat(config.getLlm().getPromptCache().getTtl()).isEqualTo("1h");
    assertThat(config.getLlm().getPromptCache().getLayout()).isEqualTo("system_and_3");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dskip.web.build=true "-Dtest=SolonAiLlmGatewayConfigTest#shouldLoadPromptCacheConfig" test`

Expected: FAIL，原因是 prompt cache 配置对象或 getter 尚不存在。

- [ ] **Step 3: 实现最小配置对象**

在 `AppConfig` 的 LLM 配置中新增 `promptCache` 子对象，默认：enabled=false、ttl=5m、layout=system_and_3。

- [ ] **Step 4: 测试通过**

Run: `mvn -Dskip.web.build=true "-Dtest=SolonAiLlmGatewayConfigTest#shouldLoadPromptCacheConfig" test`

Expected: PASS。

- [ ] **Step 5: 写 usage token 失败测试**

在 `SolonAiLlmGatewayUsageTest` 增加 cache/reasoning token 归集测试：

```java
@Test
void shouldTrackCacheAndReasoningTokens() {
    LlmUsage usage = new LlmUsage();
    usage.addInputTokens(100);
    usage.addOutputTokens(30);
    usage.addCacheReadTokens(40);
    usage.addCacheWriteTokens(20);
    usage.addReasoningTokens(10);

    assertThat(usage.getInputTokens()).isEqualTo(100);
    assertThat(usage.getOutputTokens()).isEqualTo(30);
    assertThat(usage.getCacheReadTokens()).isEqualTo(40);
    assertThat(usage.getCacheWriteTokens()).isEqualTo(20);
    assertThat(usage.getReasoningTokens()).isEqualTo(10);
}
```

- [ ] **Step 6: 实现 usage 字段并接入 gateway**

在 LLM usage 归集路径补充 input/output/cacheRead/cacheWrite/reasoning 字段。若 Solon AI 响应暂不暴露某些字段，保留 0 并确保 schema/API 可承载。

- [ ] **Step 7: 补配置样例**

在 `app.yml` 和 `config.example.yml` 增加：

```yaml
solonclaw.llm.promptCache.enabled: false
solonclaw.llm.promptCache.ttl: 5m
solonclaw.llm.promptCache.layout: system_and_3
```

- [ ] **Step 8: 运行本包测试**

Run: `mvn -Dskip.web.build=true "-Dtest=SolonAiLlmGatewayUsageTest,SolonAiLlmGatewayConfigTest,ModelMetadataServiceTest" test`

Expected: PASS。

---

## Task 2: SQLite canonical state + session search 三模式

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/storage/repository/SqliteDatabase.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/storage/repository/SqliteSessionRepository.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/engine/DefaultSessionSearchService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/tool/runtime/SessionSearchTools.java`
- Test: `src/test/java/com/jimuqu/solon/claw/SqliteAgentSessionTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/SessionSearchServiceTest.java`

- [ ] **Step 1: 写 session metadata/lineage 测试**

新增或扩展 `SqliteAgentSessionTest`，验证 session 保存 parentSessionId、title、model/provider、platformMessageId 后可读回。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dskip.web.build=true "-Dtest=SqliteAgentSessionTest" test`

Expected: FAIL，缺字段或缺 repository 方法。

- [ ] **Step 3: 扩展 SQLite schema**

在 `SqliteDatabase` schema migration 中增加缺失列，保证重复启动幂等。

- [ ] **Step 4: 实现 repository 读写**

在 `SqliteSessionRepository` 和相关 session model 中读写新增字段。

- [ ] **Step 5: 写 session_search discovery/scroll/browse 测试**

在 `SessionSearchServiceTest` 增加：

- discovery：传 query，返回匹配 session 和 snippet。
- scroll：传 sessionId + aroundMessageId，返回 anchored window。
- browse：不传 query，返回最近 session。

- [ ] **Step 6: 实现三模式**

在 `DefaultSessionSearchService` 和 `SessionSearchTools` 中明确 mode，保持工具参数兼容。

- [ ] **Step 7: 运行本包测试**

Run: `mvn -Dskip.web.build=true "-Dtest=SqliteAgentSessionTest,SessionSearchServiceTest,StorageRepositoryTest" test`

Expected: PASS。

---

## Task 3: 统一 slash command registry

**Files:**
- Create/Modify: `src/main/java/com/jimuqu/solon/claw/gateway/command/CommandRegistry.java`
- Create/Modify: `src/main/java/com/jimuqu/solon/claw/gateway/command/CommandDescriptor.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/cli/TerminalCommandCatalog.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/tui/TuiExtensionProjector.java`
- Test: `src/test/java/com/jimuqu/solon/claw/CommandEnhancementTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/TerminalCommandCatalogTest.java`

- [ ] **Step 1: 写 registry 测试**

验证 `/new`、`/retry`、`/undo`、`/model`、`/queue`、`/steer`、`/stop`、`/background`、`/tasks`、`/statusbar`、`/footer`、`/copy`、`/paste`、`/image`、`/handoff`、`/subgoal` 存在 descriptor、category、scope、aliases。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dskip.web.build=true "-Dtest=CommandEnhancementTest,TerminalCommandCatalogTest" test`

Expected: FAIL，缺 registry 或缺命令。

- [ ] **Step 3: 实现 CommandDescriptor**

字段：name、aliases、category、description、scopes、enabledByDefault。

- [ ] **Step 4: 实现 CommandRegistry**

提供 `all()`、`find(String)`、`byScope(scope)`、alias 解析。

- [ ] **Step 5: 接入 DefaultCommandService 和 TerminalCommandCatalog**

命令解析和帮助列表优先从 registry 读取。

- [ ] **Step 6: 为未实现命令提供明确响应**

对于 registry 已有但 handler 未完成的命令，返回“当前命令已登记但实现未启用”的结构化响应，避免静默失败。

- [ ] **Step 7: 运行本包测试**

Run: `mvn -Dskip.web.build=true "-Dtest=CommandEnhancementTest,TerminalCommandCatalogTest,GatewayCommandFlowTest" test`

Expected: PASS。

---

## Task 4: Tool executor + approval + file safety

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/engine/DefaultConversationOrchestrator.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/tool/runtime/DangerousCommandApprovalService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/tool/runtime/SecurityPolicyService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/tool/runtime/SolonClawFileReadWriteSkill.java`
- Create/Modify: `src/main/java/com/jimuqu/solon/claw/tool/runtime/ClarifyTools.java`
- Test: `src/test/java/com/jimuqu/solon/claw/DangerousCommandApprovalServiceTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/SecurityPolicyServiceTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/FileContextServiceTest.java`

- [ ] **Step 1: 写 hardline blocklist 测试**

验证高危 destructive 命令即使 approval mode 放宽也会被拒绝。

- [ ] **Step 2: 写 per-session approval isolation 测试**

验证 session A 的 approval 不会授权 session B。

- [ ] **Step 3: 写 file safety 测试**

覆盖设备路径、敏感系统路径、大文件提示和相对工作目录解析。

- [ ] **Step 4: 写 clarify tool 注册测试**

验证 registry 暴露 `clarify`，schema 包含 question/options。

- [ ] **Step 5: 实现最小代码**

补 hardline patterns、session-scoped approval key、file path guard、ClarifyTools。

- [ ] **Step 6: 运行本包测试**

Run: `mvn -Dskip.web.build=true "-Dtest=DangerousCommandApprovalServiceTest,SecurityPolicyServiceTest,ToolRegistryExposureTest,FileContextServiceTest" test`

Expected: PASS。

---

## Task 5: Terminal lifecycle + notifications

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/tool/runtime/ProcessRegistry.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/tool/runtime/ProcessTools.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/cli/LocalTerminalTaskRunner.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/gateway/delivery/AdapterBackedDeliveryService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/support/RuntimeFooterService.java`
- Test: `src/test/java/com/jimuqu/solon/claw/tool/runtime/ProcessRegistryTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/LocalTerminalTaskRunnerTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/DeliveryHomeChannelFallbackTest.java`

- [ ] **Step 1: 写 lifecycle 测试**

验证 background process 包含 created/running/completed/failed/timed_out 状态和 timestamps。

- [ ] **Step 2: 写 inactivity cleanup 测试**

验证超时进程可被 cleanup 标记并停止。

- [ ] **Step 3: 写 home channel 通知测试**

验证后台任务完成/失败时投递到原 source 或 home channel fallback。

- [ ] **Step 4: 实现最小 lifecycle 状态机**

补状态、时间戳、cleanup、通知触发点。

- [ ] **Step 5: 运行本包测试**

Run: `mvn -Dskip.web.build=true "-Dtest=ProcessRegistryTest,LocalTerminalTaskRunnerTest,DeliveryHomeChannelFallbackTest" test`

Expected: PASS。

---

## Task 6: Skills bundles + curator

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/context/LocalSkillService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/context/SkillDirectoryResolver.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/context/SkillCuratorService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/skillhub/service/DefaultSkillHubService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/tool/runtime/SkillHubTools.java`
- Test: `src/test/java/com/jimuqu/solon/claw/SkillCuratorServiceTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/SkillImportServiceTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/SkillSourceAdapterTest.java`

- [ ] **Step 1: 写 bundle 解析测试**

验证 bundle 可列出多个 skill，并能去重安装。

- [ ] **Step 2: 写 external dirs safety 测试**

验证外部目录只读/可写规则、路径越界拒绝、重复目录去重。

- [ ] **Step 3: 写 curator nudge 测试**

验证达到阈值后产生 skill review nudge，不重复刷屏。

- [ ] **Step 4: 实现 bundle/external dirs/curator 行为**

沿用现有 service，不引入完整插件系统。

- [ ] **Step 5: 运行本包测试**

Run: `mvn -Dskip.web.build=true "-Dtest=SkillCuratorServiceTest,SkillImportServiceTest,SkillSourceAdapterTest" test`

Expected: PASS。

---

## Task 7: MCP / ACP 补强

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/mcp/McpRuntimeService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/web/DashboardMcpService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/cli/acp/AcpStdioServer.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/cli/acp/AcpSessionManager.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/cli/acp/AcpEventSink.java`
- Modify: `acp_registry/agent.json`
- Test: `src/test/java/com/jimuqu/solon/claw/McpRuntimeServiceTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/AcpStdioServerTest.java`

- [ ] **Step 1: 写 MCP capability 测试**

验证 config 可描述 stdio/http/sse/oauth/sampling/keepalive/imageResults capability，并在 Dashboard service 输出。

- [ ] **Step 2: 写 ACP permissions/edit approval 测试**

验证 ACP session 可返回权限状态、审批事件、queue/steer 命令响应。

- [ ] **Step 3: 写 Zed registry metadata 测试**

验证 `acp_registry/agent.json` 包含名称、命令、权限、版本等必要字段。

- [ ] **Step 4: 实现缺口**

优先补 metadata、capability 描述、事件输出；协议无法完整实现的能力必须在 API 中明确 unsupported reason。

- [ ] **Step 5: 运行本包测试**

Run: `mvn -Dskip.web.build=true "-Dtest=McpRuntimeServiceTest,AcpStdioServerTest" test`

Expected: PASS。

---

## Task 8: Gateway context isolation + platform toolsets

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/gateway/service/DefaultGatewayService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/gateway/authorization/GatewayAuthorizationService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/gateway/delivery/AdapterBackedDeliveryService.java`
- Modify/Create: `src/main/java/com/jimuqu/solon/claw/gateway/service/GatewayExecutionContext.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/storage/repository/SqliteChannelStateRepository.java`
- Modify: `src/main/resources/app.yml`
- Modify: `config.example.yml`
- Test: `src/test/java/com/jimuqu/solon/claw/GatewayAuthorizationFlowTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/GatewayResilienceTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/MessageDeliveryTrackerTest.java`

- [ ] **Step 1: 写 gateway context 隔离测试**

模拟两个并发渠道消息，验证 delivery target、session、approval context 不串线。

- [ ] **Step 2: 写 platform toolsets 测试**

验证不同渠道可配置不同 enabledToolsets，未授权工具不可暴露。

- [ ] **Step 3: 写 platformMessageId canonical 测试**

验证入站消息的 platformMessageId 可持久化并按 exact id 查询。

- [ ] **Step 4: 实现 context object 和配置**

Java 侧用显式 `GatewayExecutionContext` 传递，不依赖 ThreadLocal 隐式状态，除非已有模式要求。

- [ ] **Step 5: 运行本包测试**

Run: `mvn -Dskip.web.build=true "-Dtest=GatewayAuthorizationFlowTest,GatewayResilienceTest,MessageDeliveryTrackerTest" test`

Expected: PASS。

---

## Task 9: Dashboard / TUI / doctor 验收入口

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/web/DashboardUsageController.java` or existing usage controller
- Modify: `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/web/DashboardRunService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/tui/TuiRunProjector.java`
- Modify: `web/src/api/client.ts`
- Modify: `web/src/views/jimuqu/UsageView.vue`
- Modify: `web/src/views/jimuqu/DiagnosticsView.vue`
- Modify: `web/src/views/jimuqu/RunsView.vue`
- Modify: `web/src/tui/TuiApp.tsx`
- Test: `src/test/java/com/jimuqu/solon/claw/DashboardDiagnosticOutputTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/DashboardRunServiceTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/TuiRunProjectorTest.java`

- [ ] **Step 1: 写 Dashboard backend 测试**

验证 usage 输出 cache/reasoning tokens，diagnostics 输出 provider/MCP/gateway/storage doctor 摘要，run 输出 background process lifecycle。

- [ ] **Step 2: 写 TUI projector 测试**

验证 approval events、process lifecycle、tool progress 可投影。

- [ ] **Step 3: 实现后端 API**

沿用现有 `DashboardResponse`，新增字段要保持向后兼容。

- [ ] **Step 4: 更新前端 API 类型和页面**

页面只展示后端已有字段，不做额外推断。

- [ ] **Step 5: 运行后端和前端构建**

Run: `mvn -Dskip.web.build=true "-Dtest=DashboardDiagnosticOutputTest,DashboardRunServiceTest,TuiRunProjectorTest" test`

Run: `npm --prefix web run build`

Expected: PASS。

---

## Task 10: 全量验收与补漏

**Files:**
- Modify as needed: `README.md`
- Modify as needed: `CLAUDE.md`
- Modify as needed: `AGENTS.md`
- Modify as needed: `config.example.yml`
- Modify as needed: tests touched by prior tasks

- [ ] **Step 1: 运行命名检查**

Run: `pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/check-project-naming.ps1 -CheckGitCommitSubjects -CheckGitObjectText -CheckCurrentBranchRange`

Expected: PASS。

- [ ] **Step 2: 运行后端测试**

Run: `mvn -Dskip.web.build=true test`

Expected: PASS。

- [ ] **Step 3: 运行完整打包**

Run: `mvn -DskipTests package`

Expected: PASS。

- [ ] **Step 4: 运行前端构建**

Run: `npm --prefix web run build`

Expected: PASS。

- [ ] **Step 5: 更新验收清单**

在最终回复中按功能包列出：已实现、已测试、明确裁剪、遗留风险。

---

## 自检

- Spec coverage：本计划覆盖上游差距报告中当前范围内必须做的核心能力，并将明确裁剪项排除。
- Placeholder scan：没有使用 TBD / TODO / implement later；每个任务有明确文件、测试命令、预期结果。
- Type consistency：新增类型统一使用 `PromptCachePolicy`、`LlmUsage`、`CommandRegistry`、`CommandDescriptor`、`GatewayExecutionContext`。
