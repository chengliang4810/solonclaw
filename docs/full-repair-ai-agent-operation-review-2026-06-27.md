# 阶段 4.4 AiAgent 全局操作能力审查记录

日期：2026-06-27

## 对应能力点

- 对应本地 Agent 工具系统、Dashboard API、前端操作入口和自然语言驱动能力。
- 本文记录阶段 4.4 的审查结论；具体落地项见 `docs/full-repair-ai-data-driven-implementation-2026-06-27.md`。

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

## 已补齐的高确定性缺口

1. Dashboard 专属管理 API 已补窄工具入口
   - 已补：运行管理、运行会话查询、定时任务指南、Agent 结构化查询、MCP 管理、技能维护管理、技能启停、技能文件列表、工具集查询、平台工具集管理、provider 管理、会话查询、会话轨迹保存、会话标题维护、Dashboard 搜索查询、TUI 运行时查询、用量分析、日志查询、媒体管理、状态查询、诊断总览查询、子进程环境诊断、Doctor 诊断、洞察查询、审批事件查询、审批队列查询、工作区查询、工作区文件维护、工作区配置项查询与非密配置维护、配置元数据查询、脱敏当前配置查询、网关二维码配置引导。
   - 原则：
     - 逐一补窄工具，复用 Dashboard 既有服务。
     - 不新增万能 Dashboard HTTP 工具。
     - 不复制 URL 安全、包安全、密钥脱敏、审批或配置校验逻辑。

2. `tool_gateway` 默认关闭
   - 现状：
     - `tool_gateway` 只有在对应工具开关启用时才加入工具集。
     - 这符合安全默认值，但会降低“自然语言发现可用工具”的能力。
   - 结论：
     - 不直接改默认开关。
     - 已通过一等窄工具覆盖高确定性 Dashboard 能力，不依赖打开 `tool_gateway` 来解决阶段 4.4。

3. 浏览器和 UI 操作能力已有工具
   - 现状：
     - `BrowserTools` 已注册。
     - 前端有大量页面和交互入口。
   - 结论：
     - 阶段 4.4 不把浏览器点击作为 Dashboard 能力补齐的主要路径。
     - 已优先补服务复用型窄工具，减少自然语言操作对 UI 点击的依赖。

4. Run control 已补，checkpoint rollback 暂不补普通工具
   - 已补：
     - `run_manage` 复用 `DashboardRunService`，覆盖 run detail、events、tools、subagents、recoveries、commands、recoverable、control、active_subagents、control_subagent、run、session_runs。
   - 暂不补：
     - checkpoint rollback、会话删除等破坏性入口不作为普通自然语言工具暴露；后续如要开放，需要先补明确审批或确认边界。

## 已明确不做

- 不新增“万能 Dashboard API 调用工具”。
- 不绕过现有工具开关、审批和安全策略。
- 不把高风险操作做成默认自动执行。

## 阶段结论

- 阶段 4.4 的高确定性缺口已经按“窄工具 + 服务复用 + 测试证明”完成。
- 剩余不补的入口属于高风险写入、浏览器下载、OAuth 回调或聊天运行主链，不按普通自然语言工具处理。
- 当前结论以 `docs/full-repair-ai-data-driven-implementation-2026-06-27.md` 中的实施清单和验证记录为准。

## 剩余风险

- 检查点回滚、会话删除、OAuth begin/refresh/callback/handle-401/clear、审批 resolve/revoke 仍保留在更强边界内；这不是遗漏，而是阶段内安全边界决策。
- Dashboard Chat 上传、SSE events、cancel 等聊天运行主链入口不重复包装成普通工具，避免和会话运行生命周期产生两套入口。
