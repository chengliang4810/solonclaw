# 阶段 4.4 AiAgent 全局操作能力审查记录

日期：2026-06-27

## 对应能力点

- 对应本地 Agent 工具系统、Dashboard API、前端操作入口和自然语言驱动能力。
- 本文只记录当前覆盖事实和缺口，不把 4.4 标记为完成。

## 已有自然语言工具覆盖

`DefaultToolRegistry.resolveEnabledTools()` 已向 Agent 暴露这些主要能力：

- 文件读写、补丁、Shell、进程管理、Python/Node/通用代码执行。
- Websearch、Webfetch、代码搜索。
- 配置读取、配置写入、密钥写入、配置刷新。
- 记忆、会话搜索、技能列表、技能查看、技能管理。
- Skills Hub 搜索、检查、安装、更新、卸载、tap 和审计。
- 消息发送、定时任务、Todo、Agent 管理、任务委托。
- MCP 工具提供方。
- 浏览器自动化。
- 安全审计。
- 图像生成、TTS、语音转写。
- 插件注册工具。
- 可选 `tool_gateway`，用于通过一个网关包装当前启用工具集合。

判断：

- 4.4 的主要问题不是“Agent 完全不能操作项目功能”。
- 当前工具层已经覆盖大部分核心能力，且保留审批、安全策略和工具开关。
- 继续推进应聚焦 Dashboard/API 独有功能是否缺少对应工具，而不是新造一个泛化自然语言操作框架。

## Dashboard API 覆盖面

当前后端 Dashboard API 覆盖：

- 状态、模型信息、模型 provider、默认模型、fallback 模型、模型健康检查。
- workspace config、配置 schema、原始配置和配置诊断。
- 会话列表、消息、recap、trajectory、分支树、checkpoint 预览和回滚。
- Chat run 创建、事件、取消。
- Run 详情、事件、工具调用、subagent、recoveries、命令、控制。
- Agents 增删改查和激活。
- Cron jobs 增删改查、暂停、恢复、触发、重试、运行记录。
- Skills、toolsets、platform toolsets。
- MCP 增删改、reload、connect、tools refresh、OAuth。
- Diagnostics、security audit、审批、slash confirms。
- Workspace files、persona diaries、media、logs、usage、insights、curator。

## 明显缺口候选

1. Dashboard 专属管理 API 未全部有一等工具入口
   - 例子：provider 管理、MCP 服务管理、checkpoint rollback、run control、curator apply/ignore、platform toolsets。
   - 现状：
     - Agent 可以通过文件、配置、Shell 或浏览器间接操作部分能力。
     - 但这不是稳定的一等自然语言工具路径。
   - 建议：
     - 后续逐一补“窄工具”，不要做一个能任意打 Dashboard API 的万能 HTTP 工具。

2. `tool_gateway` 默认关闭
   - 现状：
     - `tool_gateway` 只有在对应工具开关启用时才加入工具集。
     - 这符合安全默认值，但会降低“自然语言发现可用工具”的能力。
   - 建议：
     - 不直接改默认开关。
     - 后续先确认 UI 是否能清楚展示并启用 `tool_gateway`。

3. 浏览器和 UI 操作能力已有工具，但缺少覆盖证明
   - 现状：
     - `BrowserTools` 已注册。
     - 前端有大量页面和交互入口。
   - 建议：
     - 后续用一个最小 E2E 或后端工具解析测试证明：启用 browser 工具后 Agent 工具列表包含浏览器能力。

4. Run control 和 checkpoint rollback 是高价值候选
   - 理由：
     - Dashboard 已经有 `/api/runs/{runId}/control`、`/api/runs/subagents/{subagentId}/control` 和 `/api/checkpoints/{id}/rollback`。
     - Agent 如果只能通过 UI 或 Shell 间接操作，不够稳定。
   - 建议：
     - 优先补一个受审批/权限约束的 run 控制工具或 checkpoint 工具，避免自然语言操作只能依赖浏览器点击。

## 已明确不做

- 不新增“万能 Dashboard API 调用工具”。
- 不绕过现有工具开关、审批和安全策略。
- 不把高风险操作做成默认自动执行。

## 下一步

1. 先检查 `RunControlCommand`、`DashboardRunService`、checkpoint rollback 服务和现有工具类。
2. 若已有可复用服务层，优先补一个最小 run/checkpoint 管理工具。
3. 若服务层耦合 Dashboard 请求响应，先只补工具覆盖测试或文档，不硬拆大结构。

## 剩余风险

- 4.4 仍未完成；本文只是覆盖审查。
- 当前工作树仍存在未纳入本阶段提交的 `terminal-ui/package.json` 与 `terminal-ui/package-lock.json` 本地改动。
