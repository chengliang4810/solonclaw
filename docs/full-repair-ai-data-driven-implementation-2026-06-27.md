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
     - Dashboard 已有 MCP 服务端列表、保存、删除、检查、连接、重载、工具刷新和 OAuth 状态清理能力，但 Agent 自然语言路径没有一等入口。
   - 改造后：
     - 新增 `mcp_manage` 工具，复用 `DashboardMcpService`，支持 `list`、`save`、`delete`、`check`、`connect`、`reload`、`refresh_tools`、`reload_all`、`reload_all_async`、`oauth_status`、`oauth_clear`。
     - 不复制 MCP 配置、包安全、URL 安全、工具发现或 OAuth 脱敏逻辑，统一沿用 Dashboard 服务边界。
     - 增加工具暴露测试，证明默认工具列表包含 `mcp_manage` 且能解析到 `McpManageTools`。
   - 提交：`e24046e93`

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
     - 新增 `provider_manage` 工具，复用 `DashboardProviderService`，支持 `list`、`models`、`health`、`create`、`update`、`delete`、`default_model`、`fallbacks`、`remote_models`、`validate`。
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
- `git diff --check`：相关文件检查通过。
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`：通过。

## 已确认不改

- 安全审批、危险命令、包安全、协议清单和 provider 清单仍保持确定性规则为主。
- 主动协作 collector 暂不直接接入模型，因为后续已有 `ProactiveDecisionService` 的可选 LLM decision 和 `ProactiveMessageComposer` 的可选 LLM polish。
- 本轮不新增模型调用框架和新依赖。

## 剩余风险

- `DefaultContextCompressionService` 仍主要依赖规则摘要，后续阶段 4 可继续评估可选模型摘要层。
- 阶段 4.4 “AiAgent 全局操作能力”已补运行管理、MCP 管理、技能维护管理、平台工具集管理、provider 管理、会话与检查点查询、用量分析、日志查询、媒体管理、状态查询、Doctor 诊断、洞察查询、审批事件查询、工作区查询、配置元数据查询入口，但仍需要继续盘点其他 Dashboard 专属能力是否需要一等工具。
- 检查点回滚、会话删除和会话更新暂未进入 `session_manage`，后续如要开放需要先接入明确审批或确认边界。
- 当前工作树仍存在未纳入本阶段提交的 `terminal-ui/package.json` 与 `terminal-ui/package-lock.json` 本地改动。
