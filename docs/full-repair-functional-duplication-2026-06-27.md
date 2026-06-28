# 阶段 3.1 功能重复检测清单

日期：2026-06-27

## 对应能力点

- 对应本地 Dashboard / Web 控制台能力：诊断、渠道、配置、会话搜索、人格工作区、模型管理。
- 本阶段只做重复功能识别和处理建议，不直接删除或融合代码；代码融合放到阶段 3.2 逐项提交。

## 高确定性重复或遗留功能

### 1. 网关页面与渠道页面重叠

- 位置：
  - `web/src/views/solonclaw/GatewaysView.vue`
  - `web/src/views/solonclaw/ChannelsView.vue`
  - `web/src/stores/solonclaw/gateways.ts`
  - `web/src/api/solonclaw/gateways.ts`
  - `web/src/router/index.ts`
- 重叠程度：高。
- 当前处理结果：
  - `GatewaysView.vue` 已作为只读网关状态页挂载到 `/solonclaw/gateways`，侧边栏也有稳定入口。
  - `startGateway()` 与 `stopGateway()` 假操作包装已删除，页面不再渲染不支持的启停按钮。
  - `ChannelsView.vue` 继续承载渠道配置和平台工具集策略；`GatewaysView.vue` 仅展示运行状态，两者边界已拆开。
- 验证：
  - `web/tests/gatewayNavigationStatic.test.ts`
  - `web/tests/gatewayReadOnlyStatic.test.ts`

### 2. Doctor 诊断双入口

- 位置：
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardGatewayController.java`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsController.java`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardGatewayDoctorService.java`
- 重叠程度：高。
- 当前处理结果：
  - 当前源码只保留 `/api/diagnostics/doctor` 作为 Dashboard 诊断入口。
  - `DashboardGatewayController` 只保留国内渠道二维码 setup 接口，不再暴露 `/api/gateway/doctor`。
- 验证：
  - `web/tests/diagnosticsDoctorUiStatic.test.ts`
  - `rg -n "gateway/doctor|diagnostics/doctor" src/main/java web/src web/tests`

### 3. 会话搜索双入口

- 位置：
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardSessionController.java`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardSearchController.java`
  - `web/src/api/solonclaw/sessions.ts`
- 重叠程度：中高。
- 证据：
  - `/api/sessions/search` 只按 `q` 调用旧的会话搜索方法。
  - `/api/search` 支持 `sourceKey`、`sessionId`、`runId`、`toolName`、`channel`、时间范围、摘要和 limit，覆盖面更完整。
  - 前端 `searchSessions()` 已改用 `/api/search`。
- 当前处理结果：
  - 前端 `searchSessions()` 已统一调用 `/api/search`。
  - 当前源码未保留 `/api/sessions/search` 控制器入口。
  - 静态测试防止前端重新调用旧入口。
- 验证：
  - `web/tests/sessionSearchApiStatic.test.ts`
  - `rg -n "sessions/search|/api/search" web/src web/tests src/main/java`

## 中等确定性重叠功能

### 4. 配置管理与工作区配置管理分层重叠

- 位置：
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardConfigController.java`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardRuntimeConfigController.java`
  - `web/src/api/solonclaw/config.ts`
  - `web/src/components/solonclaw/settings/PlatformSettings.vue`
  - `web/src/components/solonclaw/settings/AccountSettings.vue`
- 重叠程度：中。
- 证据：
  - `/api/config` 管理结构化配置。
  - `/api/workspace-config` 管理工作区运行时配置和敏感值。
  - 前端 `fetchConfig()` 同时读取两者，再把渠道开关和凭证展示拼装到一个 `platforms` 对象。
- 判断：
  - 这不是简单重复，两个接口分别承担结构化配置和敏感运行时配置。
  - 重复点主要在前端保存渠道配置时的字段映射和多处分散调用。
- 建议：
  - 阶段 3.2 不删除后端接口。
  - 可以先抽出前端渠道配置 key 映射，减少 `saveCredentials()` 中按平台复制粘贴的分支。

### 5. 工作区文件、人格文件、记忆文件读取包装重叠

- 位置：
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardWorkspaceController.java`
  - `web/src/api/solonclaw/files.ts`
  - `web/src/api/solonclaw/persona.ts`
  - `web/src/api/solonclaw/skills.ts`
  - `web/src/views/solonclaw/MemoryView.vue`
  - `web/src/views/solonclaw/PersonaFileView.vue`
- 重叠程度：中。
- 证据：
  - 人格文件和记忆文件最终都通过 `/api/workspace/files/{key}` 读写。
  - `persona.ts` 和 `skills.ts` 各自定义了局部 `WorkspaceFile` 类型，并重复包装读取结果。
  - `skills.ts` 的 `fetchMemory()` 通过 `/api/workspace/files` 拉全量后筛选 `memory/user/soul`。
- 判断：
  - 专用页面入口需要保留；重复点在前端 API 类型和读取包装。
- 建议：
  - 阶段 3.2 可把 `WorkspaceFile` 类型和 `fetchWorkspaceFile()` 统一放到 `files.ts`。
  - 不建议改后端路径，避免扩大影响面。
- 处理结果：
  - 已将 `WorkspaceFile`、`fetchWorkspaceFile()` 和 `saveWorkspaceFile()` 统一放到 `files.ts`。
  - `persona.ts` 与 `skills.ts` 复用统一工作区文件 API，不再各自定义局部 `WorkspaceFile` 或重复拼接读写路径。

### 6. 模型配置、运行时模型、模型信息入口重叠

- 位置：
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardProviderController.java`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardStatusController.java`
  - `web/src/api/solonclaw/system.ts`
  - `web/src/views/solonclaw/ModelsView.vue`
  - `web/src/components/solonclaw/models/ProvidersPanel.vue`
- 重叠程度：中。
- 证据：
  - `/api/providers` 返回 provider 配置列表。
  - `/api/models` 返回运行时模型状态并扩展 provider payload。
  - `/api/model/info` 返回当前有效模型信息。
  - 前端 `fetchConfigModels()` 和 `fetchAvailableModels()` 都基于 `/api/providers` 做不同形状转换。
- 判断：
  - 三个后端入口面向不同问题：配置、运行状态、当前有效模型。
  - 前端转换函数存在重复，但删除后端接口风险较高。
- 建议：
  - 阶段 3.2 先合并 `system.ts` 中 provider 到 group 的转换逻辑。
  - 暂不删除 `/api/model/info`、`/api/models`、`/api/providers`。
- 处理结果：
  - 已删除前端未使用的 `fetchConfigModels()`、`ConfigModelsResponse`、`ModelGroup` 和 `ModelInfo`。
  - 前端继续通过 `fetchAvailableModels()` 统一消费 `/api/providers`，后端三个接口保持原有边界。

## 已复核但暂不处理的相似点

- `/api/workspace/diaries` 与 `/api/workspace/files`：一个是历史日志列表，一个是固定人格/记忆文件列表，边界不同。
- `/api/runs/{runId}/events` 与 `/api/chat/runs/{runId}/events`：一个是运行详情查询，一个是聊天流式事件读取，调用场景不同。
- `/api/tools/toolsets` 与 `/api/tools/platform-toolsets`：一个是工具集目录，一个是平台绑定策略，属于配套关系。
- `ChannelsView.vue` 与 `SettingsView.vue`：渠道配置是设置能力的专用入口，不是重复页面。

## 阶段 3.2 推荐顺序

1. 已完成：网关页链路改为只读状态页，删除不支持的启停假操作。
2. 已完成：会话搜索前端统一到 `/api/search`，旧 `/api/sessions/search` 入口不再保留。
3. 已完成：Doctor 只保留 `/api/diagnostics/doctor` Dashboard 主入口。
4. 已完成：前端工作区文件读取包装已统一到 `files.ts`。
5. 精简前端渠道凭证 key 映射和模型 provider 转换函数。

## 验证方式

- 本阶段为文档清单，不修改运行时代码。
- 已通过静态扫描复核：
  - 后端 `@Mapping` 路由表。
  - 前端 router、views、stores、api 调用关系。
  - 重点重复域的 controller、service 和 API 包装实现。
