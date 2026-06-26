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

## 验证

- `mvn -Dskip.web.build=true -Dtest=GoalServiceTest test`：通过。
- `mvn -Dskip.web.build=true -Dtest=ProactiveDecisionServiceTest test`：通过。
- `mvn -Dskip.web.build=true -Dtest=DashboardRunServiceTest,ToolRegistryExposureTest#shouldExposeRunManagementToolForNaturalLanguageRunControl test`：通过。
- `mvn -Dskip.web.build=true -Dtest=McpRuntimeServiceTest,ToolRegistryExposureTest#shouldExposeMcpManagementToolForNaturalLanguageServerControl test`：通过。
- `git diff --check`：相关文件检查通过。
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`：通过。

## 已确认不改

- 安全审批、危险命令、包安全、协议清单和 provider 清单仍保持确定性规则为主。
- 主动协作 collector 暂不直接接入模型，因为后续已有 `ProactiveDecisionService` 的可选 LLM decision 和 `ProactiveMessageComposer` 的可选 LLM polish。
- 本轮不新增模型调用框架和新依赖。

## 剩余风险

- `DefaultContextCompressionService` 仍主要依赖规则摘要，后续阶段 4 可继续评估可选模型摘要层。
- 阶段 4.4 “AiAgent 全局操作能力”已补运行管理和 MCP 管理入口，但仍需要继续盘点 provider、curator、platform toolsets 等 Dashboard 专属能力是否需要一等工具。
- 当前工作树仍存在未纳入本阶段提交的 `terminal-ui/package.json` 与 `terminal-ui/package-lock.json` 本地改动。
