# 阶段 4 AI 与数据驱动改造实施记录

日期：2026-06-27

## 对应能力点

- 对应本地 Agent goal 连续执行能力。
- 对应主动协作的模型决策能力。
- 本文只记录已经完成并提交的阶段 4 改造。

## 已处理项

1. 收紧 goal 完成判定
   - 位置：
     - `src/main/java/com/jimuqu/solon/claw/goal/HeuristicGoalJudge.java`
     - `src/test/java/com/jimuqu/solon/claw/GoalServiceTest.java`
   - 改造前：
     - 最后一条回复只要包含 `completed`、`已完成`、`已经完成` 就会把 standing goal 判为 done。
     - 无人值守任务中，“完成当前文档，等待最终验证”这类子步骤进展容易误停整个 goal。
   - 改造后：
     - 只把 `goal achieved`、`goal complete`、`整个目标已完成` 等强完成信号判为 done。
     - 保留 `无法继续`、`需要用户`、`need input` 等阻塞信号，避免无法推进时继续空转。
     - 增加回归测试覆盖“子步骤完成但仍待验证”的 continue 语义。
   - 提交：`386cc9164`

2. 主动协作 LLM 决策补充真实证据
   - 位置：
     - `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveDecisionService.java`
     - `src/test/java/com/jimuqu/solon/claw/ProactiveDecisionServiceTest.java`
   - 改造前：
     - LLM decision prompt 只包含 topic、title、summary、reason、actionOffer、confidence 和 priority。
     - 模型看不到候选 evidence 中的真实聊天内容、记忆线索或运行证据，容易退化成只按摘要标签判断。
   - 改造后：
     - prompt 增加脱敏后的 `evidence` 摘要。
     - 证据先通过 `SecretRedactor.redact(..., 900)` 裁剪和脱敏，避免把 token 等敏感信息送入模型。
     - 增加测试确认真实证据进入 prompt，且密钥样式内容不会泄露。
   - 提交：`aab7d5437`

3. 增加 Agent 运行管理一等工具
   - 位置：
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/RunTools.java`
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
     - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
     - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
     - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
   - 改造前：
     - Dashboard 已有 run detail、events、tools、recoveries、commands、control 和 subagent control API。
     - Agent 自然语言路径没有一等 `run_manage` 工具，处理运行控制时只能间接依赖 UI、Shell 或浏览器。
   - 改造后：
     - 新增 `run_manage` 工具，复用 `DashboardRunService`，支持 `detail`、`events`、`tools`、`subagents`、`recoveries`、`commands`、`recoverable`、`control`、`active_subagents`、`control_subagent`。
     - 生产运行时通过 `ToolConfiguration` 注入已有 `DashboardRunService`，避免复制 Dashboard 业务逻辑。
     - 增加工具暴露测试，证明默认工具列表包含 `run_manage` 且能解析到 `RunTools`。
   - 提交：`5858ed171`

4. 增加 MCP 服务端管理一等工具
   - 位置：
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/McpManageTools.java`
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
     - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
     - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
     - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
     - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
   - 改造前：
     - 已有 `mcp` 工具只暴露启用的 MCP 远端工具调用能力。
     - Dashboard 已有 MCP 服务端列表、保存、删除、检查、连接、重载、工具刷新和 OAuth 状态、刷新、401 处理、清理能力，但 Agent 自然语言路径没有一等入口。
   - 改造后：
     - 新增 `mcp_manage` 工具，复用 `DashboardMcpService`，支持 `list`、`save`、`delete`、`check`、`connect`、`reload`、`refresh_tools` / `tools_refresh`、`reload_all`、`reload_all_async`、`oauth_status`、`oauth_refresh`、`oauth_handle_401`、`oauth_clear`。
     - 不复制 MCP 配置、包安全、URL 安全、工具发现或 OAuth 脱敏逻辑，统一沿用 Dashboard 服务边界。
     - 增加工具暴露和页面动作测试，证明默认工具列表包含 `mcp_manage`、能解析到 `McpManageTools`，且页面 `tools/refresh`、`oauth/refresh`、`oauth/handle-401` 语义可通过自然语言工具执行。
   - 初始提交：`e24046e93`；后续提交继续补齐页面动作。

5. 增加技能维护管理一等工具
   - 位置：
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/CuratorManageTools.java`
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
     - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
     - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
     - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
     - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
   - 改造前：
     - Dashboard 和 `/curator` 命令已有技能维护状态、运行、暂停、恢复、报告、改进建议、应用和忽略能力。
     - Agent 工具调用路径没有一等 `curator_manage`，自然语言处理技能维护建议时需要绕到命令或 UI。
   - 改造后：
     - 新增 `curator_manage` 工具，复用 `DashboardCuratorService`，支持 `status`、`run`、`pause`、`resume`、`list`、`detail`、`improvements`、`apply`、`ignore`。
     - 不复制技能扫描、报告落库、建议应用/忽略和脱敏逻辑，统一沿用 Dashboard 服务边界。
     - 增加工具暴露测试，证明默认工具列表包含 `curator_manage` 且能解析到 `CuratorManageTools`。
   - 提交：`048483067`

6. 增加平台工具集管理一等工具
   - 位置：
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/PlatformToolsetsManageTools.java`
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
     - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
     - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
     - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
     - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
   - 改造前：
     - Dashboard 已有国内渠道平台工具集策略的 overview/update API，负责平台白名单、工具集列表规范化和配置落盘。
     - Agent 自然语言路径没有一等工具入口；`config_set` 只能写单个配置键，不能复用 Dashboard 的平台校验和结构化更新。
   - 改造后：
     - 新增 `platform_toolsets_manage` 工具，复用 `DashboardPlatformToolsetsService`，支持 `overview`、`update`。
     - 支持平台仍限定为 `feishu`、`dingtalk`、`wecom`、`weixin`、`qqbot`、`yuanbao`。
     - 增加工具暴露测试，证明默认工具列表包含 `platform_toolsets_manage` 且能解析到 `PlatformToolsetsManageTools`。
   - 提交：`b5f6c2cfe`

7. 增加模型 provider 管理一等工具
   - 位置：
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/ProviderManageTools.java`
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
     - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
     - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
     - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
     - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
   - 改造前：
     - Dashboard 已有 provider 列表、模型列表、健康检查、创建、更新、删除、默认模型、fallback、远端模型拉取和校验能力。
     - Agent 自然语言路径只能通过配置键或 UI 间接操作，不能复用 Dashboard provider 的 URL 安全、provider 校验、fallback 校验和模型列表逻辑。
   - 改造后：
     - 新增 `provider_manage` 工具，复用 `DashboardProviderService`，支持 `list`、`models`、`health` / `models_health`、`create`、`update`、`delete`、`default_model` / `set_default_model`、`fallbacks`、`remote_models` / `provider_models` / `fetch_models` / `fetch_model_list`、`validate`。
     - 工具结果统一脱敏预览，不暴露 API key 或带密 URL。
     - 增加工具暴露测试，证明默认工具列表包含 `provider_manage` 且能解析到 `ProviderManageTools`。
   - 提交：`73032a5ee`

8. 增加会话与检查点查询一等工具
   - 位置：
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/SessionManageTools.java`
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
     - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
     - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
   - 改造前：
     - Dashboard 已有会话列表、消息、recap、trajectory、分支树、最新后代、检查点列表和检查点预览能力。
     - Agent 自然语言路径没有一等 `session_manage` 工具，排查历史会话、分支和检查点时需要绕到 UI 或文件层。
   - 改造后：
     - 新增 `session_manage` 工具，复用 `DashboardSessionService`，支持 `list`、`messages`、`recap`、`trajectory`、`tree`、`latest_descendant`、`checkpoints`、`checkpoint_preview`。
     - 工具结果沿用 Dashboard 会话服务的消息脱敏、摘要裁剪和检查点预览逻辑。
     - 暂不暴露 `rollbackCheckpoint`、`deleteSession`、`updateSession` 等破坏性或写操作，避免自然语言路径绕过更强审批边界。
     - 增加工具暴露测试，证明默认工具列表包含 `session_manage` 且能解析到 `SessionManageTools`。
   - 提交：`6cb47184c`

9. 增加用量分析查询一等工具
   - 位置：
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/AnalyticsManageTools.java`
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
     - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
     - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
     - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
     - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
   - 改造前：
     - Dashboard 已有 `/api/analytics/usage`，可以按天和模型聚合 token、会话数和成本字段。
     - Agent 自然语言路径没有一等 `analytics_manage` 工具，查询用量分析只能绕到 UI 或自行读取会话数据。
   - 改造后：
     - 新增 `analytics_manage` 工具，复用 `DashboardAnalyticsService#getUsage`，支持 `usage`。
     - 生产运行时注入 `UsageEventRepository`，保持与 Dashboard 一样优先使用 usage event 成本数据，再回退会话累计 token。
     - 工具结果统一脱敏预览，避免把异常或模型标签中的敏感内容直接展开。
     - 增加工具暴露测试，证明默认工具列表包含 `analytics_manage` 且能解析到 `AnalyticsManageTools`。
   - 提交：`dff1a74c3`

10. 增加日志查询一等工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/LogsManageTools.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
      - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
    - 改造前：
      - Dashboard 已有 `/api/logs`，只允许读取 `agent`、`gateway`、`errors` 日志，并会按级别、组件和关键字过滤。
      - 该服务还会追加运行和定时任务结构化索引命中，并统一执行脱敏。
      - Agent 自然语言路径只能用通用文件读取工具绕过 Dashboard 日志查询语义，无法复用日志文件白名单、行数封顶和结构化索引。
    - 改造后：
      - 新增 `logs_manage` 工具，复用 `DashboardLogsService`，支持按 `file`、`lines`、`level`、`component`、`query` 查询。
      - 生产运行时注入 `AgentRunRepository` 与 `CronJobRepository`，保持与 Dashboard 日志页一致的运行/定时任务结构化索引补充能力。
      - 工具结果沿用 Dashboard 日志服务的文件白名单、500 行封顶和敏感信息脱敏。
      - 增加工具暴露测试，证明默认工具列表包含 `logs_manage` 且能解析到 `LogsManageTools`。
    - 提交：`b01245c28`

11. 增加媒体管理一等工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/MediaManageTools.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
      - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
    - 改造前：
      - Dashboard 已有媒体缓存列表、详情、索引刷新、下载和引用生成能力。
      - Agent 自然语言路径没有一等 `media_manage` 工具，查看或刷新媒体缓存时需要绕到 UI 或底层数据库/文件。
    - 改造后：
      - 新增 `media_manage` 工具，复用 `DashboardMediaService`，支持 `list`、`detail`、`index`、`refresh`、`download`、`reference`。
      - 工具沿用 Dashboard 媒体服务的路径保护、媒体索引和附件引用逻辑，不重复实现本地文件扫描。
      - 增加工具暴露测试，证明默认工具列表包含 `media_manage` 且能解析到 `MediaManageTools`。
    - 提交：`5d11dc000`

12. 增加状态查询一等工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/StatusManageTools.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
      - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
    - 改造前：
      - Dashboard 已有运行状态、健康快照和模型信息聚合能力。
      - Agent 自然语言路径没有一等 `status_manage` 工具，查询运行态、渠道连接态、模型能力和版本信息时需要绕到 UI 或健康接口。
    - 改造后：
      - 新增 `status_manage` 工具，复用 `DashboardStatusService`，支持 `status`、`health`、`model_info`。
      - 工具只开放只读查询入口，沿用 Dashboard 状态服务的脱敏、路径引用和工作区配置刷新逻辑。
      - 增加工具暴露测试，证明默认工具列表包含 `status_manage` 且能解析到 `StatusManageTools`。
    - 提交：`05637a9cf`

13. 增加 Doctor 诊断一等工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DoctorManageTools.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
      - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
    - 改造前：
      - Dashboard 已有 `/api/diagnostics/doctor`，可以聚合模型配置、国内渠道、工作区配置和最近关闭取证摘要。
      - Agent 自然语言路径没有一等 `doctor_manage` 工具，排查消息网关启动失败、模型配置缺失和渠道配置问题时需要绕到 UI。
    - 改造后：
      - 新增 `doctor_manage` 工具，复用 `DashboardGatewayDoctorService#doctor()`。
      - 工具只开放只读 Doctor 诊断，不暴露 `DashboardDiagnosticsController` 中审批 resolve/revoke 或安全审计类写入口。
      - 增加工具暴露测试，证明默认工具列表包含 `doctor_manage` 且能解析到 `DoctorManageTools`。
    - 提交：`d0302ba46`

14. 增加洞察查询一等工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/InsightsManageTools.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
      - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
    - 改造前：
      - Dashboard 已有 `/api/insights/overview` 与 `/api/insights/skills`，可查看会话总量、技能用量状态和 JVM 运行时概览。
      - Agent 自然语言路径没有一等 `insights_manage` 工具，查询技能使用情况和运行时洞察需要绕到 UI。
    - 改造后：
      - 新增 `insights_manage` 工具，复用 `DashboardInsightsService`，支持 `overview`、`skills`。
      - 工具只开放只读洞察查询，不改技能状态、不写入用量数据。
      - 增加工具暴露测试，证明默认工具列表包含 `insights_manage` 且能解析到 `InsightsManageTools`。
    - 提交：`057de2af1`

15. 增加审批事件查询一等工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/ApprovalEventsManageTools.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
      - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
    - 改造前：
      - Dashboard 已有 `/api/approval/events` 与 `/api/approval/stats`，可查看最近审批事件和审批统计。
      - Agent 自然语言路径没有一等 `approval_events_manage` 工具，排查审批状态和近期审批决策需要绕到 UI。
    - 改造后：
      - 新增 `approval_events_manage` 工具，复用 `DashboardApprovalEventsService`，支持 `events`、`stats`。
      - 工具只开放只读查询，不暴露 `DashboardApprovalEventsService#clear()`。
      - 增加工具暴露测试，证明默认工具列表包含 `approval_events_manage` 且能解析到 `ApprovalEventsManageTools`。
    - 提交：`8038d04c0`

16. 增加工作区查询一等工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/WorkspaceManageTools.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
      - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
    - 改造前：
      - Dashboard 已有人格工作区文件列表、单文件读取、日记列表和日记读取能力。
      - Agent 自然语言路径没有一等 `workspace_manage` 工具，查看人格工作区文件和日记内容需要绕到 UI。
    - 改造后：
      - 新增 `workspace_manage` 工具，复用 `DashboardWorkspaceService`，支持 `files`、`file`、`diaries`、`diary`。
      - 工具只开放只读查询，不暴露 `saveFile`、`restoreFile` 和 `downloadFile`，避免自然语言入口扩大写入或下载风险面。
      - 增加工具暴露测试，证明默认工具列表包含 `workspace_manage` 且能解析到 `WorkspaceManageTools`。
    - 提交：`075db52c4`

17. 增加配置元数据查询一等工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/ConfigManageTools.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
      - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
    - 改造前：
      - Dashboard 已有配置 schema、默认值和诊断读取能力。
      - Agent 自然语言路径已有单键 `config_get`、`config_set`、`config_set_secret`、`config_refresh`，但没有一等入口查看 Dashboard 配置元数据和诊断。
    - 改造后：
      - 新增 `config_manage` 工具，复用 `DashboardConfigService`，支持 `schema`、`defaults`、`diagnostics`。
      - 工具只开放低风险只读查询，不暴露 `getConfig`、`getRaw`、`saveConfig`、`saveRaw`、runtime config `reveal/remove` 等可能泄露密钥或改写整份配置的入口。
      - 增加工具暴露测试，证明默认工具列表包含 `config_manage` 且能解析到 `ConfigManageTools`。
    - 提交：`1aa80b75a`

18. 增加网关二维码配置引导一等工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/GatewaySetupManageTools.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
      - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
    - 改造前：
      - Dashboard 已有微信、飞书、钉钉二维码 setup 的启动和 ticket 查询能力。
      - Agent 自然语言路径没有一等 `gateway_setup_manage` 工具，配置国内渠道二维码引导时需要绕到 UI。
    - 改造后：
      - 新增 `gateway_setup_manage` 工具，复用 `WeixinQrSetupService` 与 `DomesticQrSetupService`，支持 `start`、`get`，渠道支持 `weixin`、`feishu`、`dingtalk`。
      - 工具只做现有 setup 服务路由，不自行读取、写入或返回 token/clientSecret；二维码确认后落配置仍由原 Dashboard setup 服务负责。
      - 增加工具暴露测试，证明默认工具列表包含 `gateway_setup_manage` 且能解析到 `GatewaySetupManageTools`。
    - 提交：`a5f0c65ee`

19. 增加审批队列查询一等工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/ApprovalQueueManageTools.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
      - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
    - 改造前：
      - Dashboard 已有 pending approvals、approval history、always approvals、slash confirms 查询能力。
      - Agent 自然语言路径只有 `approval_events_manage` 事件统计，不能直接查看当前待处理审批队列和 slash confirm 队列。
    - 改造后：
      - 新增 `approval_queue_manage` 工具，复用 `DashboardDiagnosticsService`，支持 `pending`、`history`、`always`、`slash_confirms`、`summary`。
      - 工具只开放只读查询，不暴露 `resolveApproval`、`resolveSlashConfirm`、`revokeAlwaysApproval` 等会改变审批状态的动作。
      - 增加工具暴露和 summary 调用测试，证明默认工具列表包含 `approval_queue_manage` 且自然语言工具可返回四类审批队列。
    - 提交：`3ca8c2351`

20. 增加 Dashboard 搜索查询一等工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/SearchManageTools.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
    - 改造前：
      - Dashboard 已有 `/api/search`，支持按 source、session、run、tool、channel、time 和 summarize 查询历史会话、运行和工具调用。
      - Agent 自然语言路径只有 `session_search`，默认绑定当前 sourceKey，无法直接使用 Dashboard 搜索的运行维度过滤能力。
    - 改造后：
      - 新增 `search_manage` 工具，复用 `SessionSearchService`，支持 Dashboard 搜索同类过滤字段，并返回与 `/api/search` 对齐的 `results` 和 tokenizer 信息。
      - 工具只开放只读查询，不新增索引写入、导出或删除能力；返回内容继续使用 `SecretRedactor` 脱敏。
      - 增加工具暴露和实际调用测试，证明默认工具列表包含 `search_manage` 且自然语言工具可返回 Dashboard 搜索结构。
    - 提交：`990caa9df`

21. 增加 TUI 运行时查询一等工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/TuiRuntimeManageTools.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
    - 改造前：
      - Dashboard 已有 `/api/tui/rpc`，TUI 前端可查询 setup 状态、模型选项、渠道选项、渠道状态和配置值。
      - Agent 自然语言路径没有一等 TUI runtime 查询工具，无法直接检查独立终端前端看到的 setup/model/channel/config 状态。
    - 改造后：
      - 新增 `tui_runtime_manage` 工具，复用 `TuiRuntimeProtocolService`，支持 `setup_status`、`model_options`、`channel_options`、`channel_status`、`config_get`，并兼容前端 RPC 点号读方法名 `setup.status`、`model.options`、`channel.options`、`channel.status`、`config.get`。
      - 后续补齐 TUI Runtime 页面级写动作，支持 `model_save_key`、`channel_save`、`channel_qr_start`、`channel_qr_get`，并通过注册处注入既有二维码 setup 服务。
      - 工具不暴露 `config.set` 这类当前 TUI Runtime 页面没有使用的写入入口，避免扩大自然语言操作面。
      - 增加工具暴露和实际调用测试，证明默认工具列表包含 `tui_runtime_manage`、自然语言工具可返回 setup 状态，并可脱敏保存模型密钥。
    - 提交：`6df18f509`、`943172367`

22. 增加工作区配置查询一等工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/WorkspaceConfigManageTools.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/main/java/com/jimuqu/solon/claw/bootstrap/ToolConfiguration.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
      - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
    - 改造前：
      - Dashboard 已有 `/api/workspace-config` 只读 GET，可返回工作区配置项、分类、说明、是否已设置和脱敏值。
      - Agent 自然语言路径只有 `config_manage` 的 schema/defaults/diagnostics 元数据查询，不能直接查看当前工作区配置项状态。
    - 改造后：
      - 新增 `workspace_config_manage` 工具，复用 `DashboardRuntimeConfigService#getConfigItems()`，支持 `items` 只读查询。
      - 工具只返回已脱敏的 `redacted_value` 和配置项元数据，不暴露 `set`、`delete`、`reveal` 等写入或明文读取入口。
      - 增加工具暴露和实际调用测试，证明默认工具列表包含 `workspace_config_manage` 且自然语言工具可返回工作区配置项。
    - 提交：`5b0653903`

23. 增加诊断总览查询一等工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DiagnosticsManageTools.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
    - 改造前：
      - Dashboard 已有 `/api/diagnostics`，可返回 runtime、providers、channels、stream health、tools、MCP、安全策略和运行诊断总览。
      - Agent 自然语言路径有 `doctor_manage`、`approval_queue_manage` 和 `security_audit` 等分项工具，但不能直接查询 Dashboard 诊断总览。
    - 改造后：
      - 新增 `diagnostics_manage` 工具，复用 `DashboardDiagnosticsService#diagnostics()`，返回与 `/api/diagnostics` 对齐的只读总览。
      - 工具不暴露安全审计 POST、审批 resolve 等可执行或状态变更入口。
      - 顺手补强 `DashboardDiagnosticsService` 在测试或局部装配中 `toolRegistry` 为空时的降级，避免诊断总览因为可选依赖缺失整体失败。
      - 增加工具暴露和实际调用测试，证明默认工具列表包含 `diagnostics_manage` 且自然语言工具可返回 runtime/tools/security 诊断段。
    - 提交：`aa8efb151`

24. 增加工具集查询一等工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/ToolsetsManageTools.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
    - 改造前：
      - Dashboard 已有 `/api/tools/toolsets`，可返回 code、agent、memory、skills、messaging、automation、config、gateway 等工具集分组。
      - Agent 自然语言路径已有技能本体查询和平台工具集策略管理，但不能直接查看 Dashboard 工具集分组视图。
    - 改造后：
      - 新增 `toolsets_manage` 工具，复用 `DashboardSkillsService#getToolsets()`，返回与 `/api/tools/toolsets` 对齐的只读工具集列表和数量。
      - 工具只开放查询，不新增启用、禁用或策略写入口，避免和平台工具集管理、cron enabled_toolsets 等已有写路径重复。
      - 增加工具暴露和实际调用测试，证明默认工具列表包含 `toolsets_manage` 且自然语言工具可返回 code/skills 工具集。
    - 提交：`78cf4119f`

25. 增加会话轨迹保存工具动作
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/SessionManageTools.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
    - 改造前：
      - Dashboard 已有 `/api/sessions/{id}/trajectory/save`，可把会话轨迹保存到工作区 artifact。
      - `session_manage` 已支持 list、messages、recap、trajectory、tree、latest_descendant、checkpoints、checkpoint_preview，但自然语言工具不能触发同一保存能力。
    - 改造后：
      - `session_manage` 增加 `save_trajectory` 动作，复用 `DashboardSessionService#saveTrajectory()`。
      - 保存结果沿用 Dashboard artifact 服务的 `workspace://artifacts/...` 路径，不向工具结果暴露宿主机绝对路径。
      - 增加实际调用测试，证明自然语言工具可保存 trajectory 并返回 `trajectory_samples.jsonl` 工作区引用。
    - 提交：`5526a081a`

26. 增加工作区文件维护工具动作
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/WorkspaceManageTools.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
    - 改造前：
      - Dashboard 已有 `/api/workspace/files/{key}` 保存和 `/api/workspace/files/{key}/restore` 恢复接口，且文件 key 由 `PersonaWorkspaceService` 固定控制。
      - `workspace_manage` 只支持 files、file、diaries、diary，只能读取人格工作区内容，不能通过自然语言维护 Dashboard 同一批受控文件。
    - 改造后：
      - `workspace_manage` 增加 `save_file` 和 `restore_file` 动作，复用 `DashboardWorkspaceService#saveFile()` 与 `restoreFile()`。
      - 维护范围仍限定在 Dashboard 人格工作区固定 key，不开放任意路径写入或下载能力。
      - 增加实际调用测试，证明自然语言工具可保存并恢复 `agents` 受控工作区文件。
    - 提交：`791e8509b`

27. 增加工作区配置维护工具动作
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/WorkspaceConfigManageTools.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
    - 改造前：
      - Dashboard 已有 `/api/workspace-config` 的非密配置 set/remove 能力，服务层会校验配置项是否受支持，并区分密钥与普通配置。
      - `workspace_config_manage` 只支持 `items`，自然语言路径只能查看已脱敏配置项，不能维护 Dashboard 同一批普通工作区配置。
    - 改造后：
      - `workspace_config_manage` 增加 `set` 和 `remove` 动作，`set` 只调用 `DashboardRuntimeConfigService#writeNonSecret()`，`remove` 复用 `remove()`。
      - 工具仍不开放 `reveal`，也不允许通过该入口写入密钥配置；密钥更新继续走已有 `config_set_secret` 边界。
      - 增加实际调用测试，证明自然语言工具可维护非密配置，并会拒绝 `providers.default.apiKey` 这类密钥配置。
    - 提交：`cd4b96f83`

28. 增加会话标题维护工具动作
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/SessionManageTools.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
    - 改造前：
      - Dashboard 已有 `/api/sessions/{id}` 的标题更新能力，服务层会校验会话存在并复用 120 字符标题限制。
      - `session_manage` 已支持会话查询、轨迹、轨迹保存、分支树和检查点预览，但自然语言工具不能维护会话标题。
    - 改造后：
      - `session_manage` 增加 `update_title` 动作，复用 `DashboardSessionService#updateSession()`。
      - 工具只开放标题维护，不开放会话删除或检查点回滚，避免把破坏性操作混入普通自然语言入口。
      - 增加实际调用测试，证明自然语言工具可更新会话标题，并持久写回会话仓库。
    - 提交：`5b67d268c`

29. 增加技能启停工具动作
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/context/LocalSkillService.java`
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/SkillTools.java`
      - `src/test/java/com/jimuqu/solon/claw/MemoryAndSkillsTest.java`
    - 改造前：
      - Dashboard 已有 `/api/skills/toggle`，可维护技能全局启停偏好。
      - `skill_manage` 已支持技能创建、编辑、补丁、删除和支持文件维护，但自然语言路径不能复用 Dashboard 的全局启停能力。
    - 改造后：
      - `LocalSkillService` 增加 `setGlobalVisible()`，复用已有 `SqlitePreferenceStore#setSkillEnabledGlobal()`。
      - `skill_manage` 增加 `toggle` 动作和 `enabled` 参数，用同一技能工具入口维护全局可见性。
      - 增加实际调用测试，证明自然语言工具禁用后 `skills_list` 不再返回该技能，重新启用后恢复可见。
    - 提交：`2c4556ea9`

30. 补齐运行会话查询工具动作
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/RunTools.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
      - `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`
    - 改造前：
      - Dashboard 已有 `/api/runs/{runId}` 和 `/api/sessions/{sessionId}/runs`，可按 runId 查看单次运行，也可按 sessionId 查看会话运行列表。
      - `run_manage` 已支持详情、事件、工具调用、子 Agent、恢复记录、命令、可恢复运行和控制动作，但缺少 Dashboard 同名的两个基础只读查询动作。
      - 测试环境注册表没有注入 `DashboardRunService`，导致新增运行查询工具会落到 `run service unavailable`。
    - 改造后：
      - `run_manage` 增加 `run` 和 `session_runs` 动作，复用 `DashboardRunService#run()` 与 `sessionRuns()`。
      - `session_runs` 增加显式 `session_id` 参数，并保留旧 `payload_json.session_id` 后备，方便自然语言工具调用。
      - 测试环境补齐 `DashboardRunService` 注入，增加实际调用测试，证明自然语言工具可查询单次运行和会话运行列表。
    - 提交：`1c21d9f77`

31. 补齐定时任务指南工具动作
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/CronjobTools.java`
      - `src/test/java/com/jimuqu/solon/claw/DefaultCronSchedulerTest.java`
    - 改造前：
      - Dashboard 已有 `/api/cron/jobs/guide`，可返回定时任务使用指南。
      - `cronjob` 工具已有 policy、list、status、next、inspect、history 和维护动作，但自然语言路径不能直接读取 Dashboard 同源 guide。
    - 改造后：
      - `cronjob` 增加 `guide`/`help` 只读动作，复用 `CronJobService#guide()`。
      - 工具说明补充 guide 和 policy 动作，保持 Agent 调用时能看到同一能力。
      - 增加红绿测试，证明 `cronjob(action=guide)` 返回成功状态和 guide 数据。
    - 提交：`9162766e0`

32. 增加 Agent 结构化查询工具动作
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/AgentTools.java`
      - `src/test/java/com/jimuqu/solon/claw/AgentMechanismTest.java`
    - 改造前：
      - Dashboard Agent 接口已有结构化 list/get 能力，可返回 Agent 列表、当前会话激活状态和 Agent 配置详情。
      - `agent_manage` 只有 slash-command 风格 `args` 参数，Agent 通过自然语言查询时需要拼命令文本，结果也主要放在 preview 中。
    - 改造后：
      - `agent_manage` 增加结构化 `action`、`name`、`session_id` 和 `args` 参数。
      - 先开放 `list`、`get`、`show`、`detail` 只读结构化查询；后续补齐 `activate/use`，可按显式 `session_id` 切换 Dashboard 会话的当前 Agent。
      - 创建、更新、删除仍走原有受控文本命令路径，避免把全量 CRUD 混入普通自然语言入口。
      - 查询结果写入 `result`，并继续对预览和结构化字段做脱敏。
      - 增加红绿测试，证明自然语言工具可结构化查询 Agent 列表、单个 Agent 详情，并可切换显式 Dashboard 会话的 Agent。
    - 提交：`02c36d2b9`、`ee178c0ed`

33. 增加脱敏当前配置查询工具动作
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/ConfigManageTools.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
    - 改造前：
      - Dashboard 已有 `/api/config` 可读取当前配置，`/api/config/raw` 可读取原始 YAML。
      - `config_manage` 只暴露 schema、defaults、diagnostics，自然语言路径不能直接读取当前配置状态。
      - 原始 YAML 和当前配置可能包含 `password` 类型字段，不能直接作为 Agent 工具数据返回。
    - 改造后：
      - `config_manage` 增加 `current`/`config` 只读动作，返回 `config` 结构化字段。
      - 工具层复用 Dashboard schema 中的 `password` 字段定义，递归遮盖当前配置中的密钥值。
      - 不暴露 raw YAML，不增加写入能力。
      - 增加红绿测试，证明自然语言工具可读取当前配置且不会泄露网关注入密钥和 sudo 密码。
    - 提交：`eb33913ae`

34. 增加技能文件列表工具
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/SkillTools.java`
      - `src/main/java/com/jimuqu/solon/claw/support/constants/ToolNameConstants.java`
      - `src/main/java/com/jimuqu/solon/claw/web/DashboardSkillsService.java`
      - `src/test/java/com/jimuqu/solon/claw/MemoryAndSkillsTest.java`
    - 改造前：
      - Dashboard 已有 `/api/skills/files`，可列出某个技能的 `SKILL.md` 和支持文件。
      - Agent 自然语言工具可 `skills_list` 和 `skill_view`，但需要先知道支持文件路径，不能先列出技能文件清单。
    - 改造后：
      - 增加 `skill_files` 只读工具，复用 `LocalSkillService#viewSkill` 的技能解析和 Agent scope 过滤。
      - `skill_files` 返回主文件和可见 linked support files，不读取文件正文，不新增写入能力。
      - Dashboard 工具集常量同步加入 `skill_files`，保证工具集展示与实际工具一致。
      - 增加红绿测试，证明自然语言路径可列出技能支持文件。
    - 提交：`f45a7d0fe`

35. 增加子进程环境诊断工具动作
    - 位置：
      - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DiagnosticsManageTools.java`
      - `src/test/java/com/jimuqu/solon/claw/ToolRegistryExposureTest.java`
    - 改造前：
      - Dashboard 已有 `/api/diagnostics/subprocess-environment/probe`，可按名称探测子进程环境变量传递状态。
      - `diagnostics_manage` 只暴露诊断总览，自然语言路径不能复用 Dashboard 同源的子进程环境诊断能力。
    - 改造后：
      - `diagnostics_manage` 增加 `subprocess_environment`、`subprocess-environment`、`env` 动作。
      - 工具接收 `names_json` JSON 数组，复用 `DashboardDiagnosticsService#subprocessEnvironmentProbe()` 构造同源诊断结果。
      - 敏感环境变量名称和值继续沿用 Dashboard 诊断服务的脱敏行为。
      - 只新增只读诊断，不增加命令执行、外部访问或配置写入能力。
    - 提交：`9b124d788`

36. 更新 TUI 品牌图案
    - 位置：
      - `terminal-ui/src/banner.ts`
      - `terminal-ui/src/__tests__/banner.test.ts`
    - 改造前：
      - 顶部大标题源码已渲染为 `SOLON CLAW` 字间距版本，并已有无连字符测试覆盖。
      - `Available Tools` 左侧的会话 hero 仍是旧蛇杖/螺旋状块图案，与 Solon favicon 不一致。
    - 改造后：
      - 保留顶部 `SOLON CLAW` 无连字符标题。
      - 将会话 hero 替换为 Solon favicon 的终端块状近似图案，不引入图片渲染依赖。
      - 更新单测，要求 hero 包含圆环/中空结构并排除旧蛇杖图案关键行。
    - 提交：`c5e872f40`

## 验证

- `mvn -Dskip.web.build=true -Dtest=GoalServiceTest test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ProactiveDecisionServiceTest test`：通过。
- `mvn -Dskip.web.build=true -Dtest=DashboardRunServiceTest,ToolRegistryExposureTest#shouldExposeRunManagementToolForNaturalLanguageRunControl test`：通过。
- `mvn -Dskip.web.build=true -Dtest=McpRuntimeServiceTest,ToolRegistryExposureTest#shouldExposeMcpManagementToolForNaturalLanguageServerControl test`：通过。
- `mvn -Dskip.web.build=true -Dtest=DashboardCuratorServiceTest,SkillCuratorServiceTest,ToolRegistryExposureTest#shouldExposeCuratorManagementToolForNaturalLanguageSkillMaintenance test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest#shouldExposePlatformToolsetsManagementToolForNaturalLanguageChannelPolicy test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ProviderDisplayGroupingTest,RuntimeSetupServiceTest,ToolRegistryExposureTest#shouldExposeProviderManagementToolForNaturalLanguageModelConfiguration test`：通过。
- `mvn -Dskip.web.build=true -Dtest=DashboardSessionServiceTest,ToolRegistryExposureTest#shouldExposeSessionManagementToolForNaturalLanguageSessionInspection test`：通过。
- `mvn -Dskip.web.build=true -Dtest=SessionUsageTrackingTest,UsagePricingTest#analyticsUsesUsageEventsForCostsAndFallsBackToSessionTokensWithoutPricing,UsagePricingTest#analyticsCountsAllUsageEventsWithoutRepositoryLimitTruncation,ToolRegistryExposureTest#shouldExposeAnalyticsManagementToolForNaturalLanguageUsageInspection test`：通过。
- `mvn -Dskip.web.build=true -Dtest=DashboardLogsServiceTest,ToolRegistryExposureTest#shouldExposeLogsManagementToolForNaturalLanguageLogInspection test`：通过。
- `mvn -Dskip.web.build=true -Dtest=DashboardMediaServiceTest,ToolRegistryExposureTest#shouldExposeMediaManagementToolForNaturalLanguageMediaInspection test`：通过。
- `mvn -Dskip.web.build=true -Dtest=DashboardStatusServiceTest,ToolRegistryExposureTest#shouldExposeStatusManagementToolForNaturalLanguageRuntimeInspection test`：通过。
- `mvn -Dskip.web.build=true -Dtest=DashboardDiagnosticOutputTest#shouldSummarizeDoctorIssuesAndNextActionsInStableOrder,ToolRegistryExposureTest#shouldExposeDoctorManagementToolForNaturalLanguageGatewayDiagnostics test`：通过。
- `mvn -Dskip.web.build=true -Dtest=SkillUsageTrackerTest,ToolRegistryExposureTest#shouldExposeInsightsManagementToolForNaturalLanguageInsightInspection test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest#shouldExposeApprovalEventsManagementToolForNaturalLanguageApprovalInspection test`：通过。
- `mvn -Dskip.web.build=true -Dtest=PersonaWorkspaceServiceTest,ToolRegistryExposureTest#shouldExposeWorkspaceManagementToolForNaturalLanguageWorkspaceInspection test`：通过。
- `mvn -Dskip.web.build=true -Dtest=RuntimeRefreshBehaviorTest,ToolRegistryExposureTest#shouldExposeConfigManagementToolForNaturalLanguageConfigInspection test`：通过。
- `mvn -Dskip.web.build=true -Dtest=WeixinQrSetupServiceTest,DomesticQrSetupServiceTest,ToolRegistryExposureTest#shouldExposeGatewaySetupManagementToolForNaturalLanguageQrSetup test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest#shouldExposeApprovalEventsManagementToolForNaturalLanguageApprovalInspection+shouldExposeApprovalQueueManagementToolForNaturalLanguageApprovalInspection+shouldInspectApprovalQueuesThroughNaturalLanguageTool test`：通过。
- `mvn -Dskip.web.build=true -Dtest=SessionSearchServiceTest#shouldRedactSecretsFromDashboardSearchResults,ToolRegistryExposureTest#shouldExposeDashboardSearchManagementToolForNaturalLanguageSearch+shouldSearchDashboardResultsThroughNaturalLanguageTool test`：通过。
- `mvn -Dskip.web.build=true -Dtest=TerminalUiRpcServiceTest,ToolRegistryExposureTest#shouldExposeTuiRuntimeManagementToolForNaturalLanguageSetupInspection+shouldInspectTuiRuntimeSetupThroughNaturalLanguageTool test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest#shouldExposeWorkspaceConfigManagementToolForNaturalLanguageConfigInspection+shouldInspectWorkspaceConfigItemsThroughNaturalLanguageTool,RuntimeConfigResolverTest#shouldSeparateRuntimeConfigNonSecretWritesSecretUpdatesAndReveal test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest#shouldExposeDiagnosticsManagementToolForNaturalLanguageDiagnosticsInspection+shouldInspectDashboardDiagnosticsThroughNaturalLanguageTool,DashboardDiagnosticOutputTest#shouldRedactGatewayDoctorAndDiagnosticsOutput test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest#shouldExposeToolsetsManagementToolForNaturalLanguageToolsetInspection+shouldInspectDashboardToolsetsThroughNaturalLanguageTool test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest#shouldExposeSessionManagementToolForNaturalLanguageSessionInspection+shouldSaveSessionTrajectoryThroughNaturalLanguageTool test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest#shouldExposeWorkspaceManagementToolForNaturalLanguageWorkspaceInspection+shouldSaveAndRestoreWorkspaceFileThroughNaturalLanguageTool test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest#shouldExposeWorkspaceConfigManagementToolForNaturalLanguageConfigInspection+shouldInspectWorkspaceConfigItemsThroughNaturalLanguageTool+shouldSetAndRemoveWorkspaceConfigThroughNaturalLanguageTool test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest#shouldExposeSessionManagementToolForNaturalLanguageSessionInspection+shouldSaveSessionTrajectoryThroughNaturalLanguageTool+shouldUpdateSessionTitleThroughNaturalLanguageTool test`：通过。
- `mvn -Dskip.web.build=true -Dtest=MemoryAndSkillsTest#shouldToggleSkillVisibilityThroughNaturalLanguageTool+shouldPreprocessSkillTemplateVarsBeforeSkillView,AgentMechanismTest#shouldExposeAgentManageTool test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest#shouldInspectSessionRunsThroughNaturalLanguageTool test`：通过。
- `mvn -Dskip.web.build=true -Dtest=DashboardRunServiceTest,ToolRegistryExposureTest#shouldExposeRunManagementToolForNaturalLanguageRunControl+shouldInspectSessionRunsThroughNaturalLanguageTool test`：通过。
- `mvn -Dskip.web.build=true -Dtest=DefaultCronSchedulerTest#shouldExposeCronjobGuideThroughTool test`：先红后绿。
- `mvn -Dskip.web.build=true -Dtest=DefaultCronSchedulerTest#shouldExposeCronjobGuideThroughTool+shouldExposeCronjobPolicyThroughTool+shouldExposeCronjobGlobalStatusAndRetryAliases test`：通过。
- `mvn -Dskip.web.build=true -Dtest=AgentMechanismTest#shouldInspectAgentsThroughStructuredToolActions test`：先红后绿。
- `mvn -Dskip.web.build=true -Dtest=AgentMechanismTest#shouldExposeAgentManageTool+shouldInspectAgentsThroughStructuredToolActions+shouldRedactSecretsFromAgentToolErrors+shouldRedactSecretsFromAgentToolSuccessPreviewOnly+shouldAllowAgentManageToolThroughAgentAllowlist test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest#shouldInspectCurrentConfigThroughNaturalLanguageToolWithoutRevealingSecrets test`：先红后绿。
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest#shouldExposeConfigManagementToolForNaturalLanguageConfigInspection+shouldInspectCurrentConfigThroughNaturalLanguageToolWithoutRevealingSecrets+shouldExposeWorkspaceConfigManagementToolForNaturalLanguageConfigInspection+shouldInspectWorkspaceConfigItemsThroughNaturalLanguageTool+shouldSetAndRemoveWorkspaceConfigThroughNaturalLanguageTool test`：通过。
- `mvn -Dskip.web.build=true -Dtest=MemoryAndSkillsTest#shouldListSkillSupportFilesThroughNaturalLanguageTool test`：先红后绿。
- `mvn -Dskip.web.build=true -Dtest=MemoryAndSkillsTest#shouldPreprocessSkillTemplateVarsBeforeSkillView+shouldToggleSkillVisibilityThroughNaturalLanguageTool+shouldListSkillSupportFilesThroughNaturalLanguageTool+shouldFilterPromptAndSkillToolsByAgentSkills,ToolRegistryExposureTest#shouldExposeBuiltinSearchTools+shouldExposeToolsetsManagementToolForNaturalLanguageToolsetInspection+shouldInspectDashboardToolsetsThroughNaturalLanguageTool test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest#shouldProbeSubprocessEnvironmentThroughNaturalLanguageDiagnosticsTool test`：先红后绿。
- `mvn -Dskip.web.build=true -Dtest=ToolRegistryExposureTest#shouldExposeDiagnosticsManagementToolForNaturalLanguageDiagnosticsInspection+shouldInspectDashboardDiagnosticsThroughNaturalLanguageTool+shouldProbeSubprocessEnvironmentThroughNaturalLanguageDiagnosticsTool+shouldExposeApprovalQueueManagementToolForNaturalLanguageApprovalInspection+shouldInspectApprovalQueuesThroughNaturalLanguageTool,DashboardDiagnosticOutputTest#shouldRedactGatewayDoctorAndDiagnosticsOutput test`：通过。
- `npm test --prefix terminal-ui -- banner.test.ts`：先红后绿，通过。
- `git diff --check`：相关文件检查通过。
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`：通过。

## 已确认不改

- 安全审批、危险命令、包安全、协议清单和 provider 清单仍保持确定性规则为主。
- 主动协作 collector 暂不直接接入模型，因为后续已有 `ProactiveDecisionService` 的可选 LLM decision 和 `ProactiveMessageComposer` 的可选 LLM polish。
- 本轮不新增模型调用框架和新依赖。
- Dashboard 接口二次盘点后，暂不把以下入口补成普通自然语言工具：
  - `DashboardChatController` 的上传、启动 chat run、SSE events 和 cancel，属于聊天运行主链或浏览器会话流，不作为重复工具入口。
  - `DashboardConfigController` 的 raw YAML 读写，已有 `config_manage(current)` 脱敏读取和 `workspace_config_manage` 受控维护替代。
  - `DashboardWorkspaceController` 的 `/api/solonclaw/download`，属于浏览器下载入口；自然语言工具已可读取同一受控文件内容。
  - 检查点回滚、会话删除、OAuth begin/refresh/callback/handle-401/clear、审批 resolve/revoke 等入口，继续保留审批或 UI 边界，不混入普通自然语言工具。

## 剩余风险

- `DefaultContextCompressionService` 仍主要依赖规则摘要，后续阶段 4 可继续评估可选模型摘要层。
- 阶段 4.4 “AiAgent 全局操作能力”已补运行管理、运行会话查询、定时任务指南、Agent 结构化查询与会话切换、MCP 管理、技能维护管理、技能启停、技能文件列表、工具集查询、平台工具集管理、provider 管理、会话与检查点查询、会话轨迹保存、会话标题维护、Dashboard 搜索查询、TUI 运行时查询与页面级 setup 写入、用量分析、日志查询、媒体管理、状态查询、诊断总览查询、子进程环境诊断、Doctor 诊断、洞察查询、审批事件查询、审批队列查询、工作区查询、工作区文件维护、工作区配置项查询与非密配置维护、配置元数据查询、脱敏当前配置查询、网关二维码配置引导入口；剩余 Dashboard 专属入口主要是高风险写入、浏览器下载、OAuth 回调或聊天运行主链，暂不按普通工具补齐。
- 检查点回滚和会话删除暂未进入 `session_manage`，后续如要开放需要先接入明确审批或确认边界。
- 当前工作树仍存在未纳入本阶段提交的 `terminal-ui/package.json` 与 `terminal-ui/package-lock.json` 本地改动。
