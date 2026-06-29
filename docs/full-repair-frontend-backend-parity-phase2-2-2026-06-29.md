# 阶段 2.2 前端超前后端接口审计记录

日期：2026-06-29

## 对应外部对标能力点

- Dashboard-first setup：前端配置、二维码 setup、诊断、运行记录和 MCP 操作必须全部落到后端真实接口。
- Agent 运行态观察：会话、运行、检查点、子代理、工具调用和日志等前端入口不能调用不存在的后端能力。
- 国内渠道接入：渠道二维码 setup、媒体缓存、平台工具集策略等 Web 操作必须与后端控制器保持一致。

## 审计口径

本阶段只处理“前端已有但后端完全没有”的功能缺口。审计时排除了以下非缺口项：

- `web/tests` 中只做源码字符串断言的静态测试。
- 前端页面路由、静态资源、外部模型提供方 URL。
- 文档中的旧路径描述。
- 已在前端明确标记为未开放、且没有页面实际操作入口的登录 stub。

## 审计方法

1. 提取 `web/src/api/solonclaw/*.ts` 与少量直接 `dashboardFetch` 调用中的运行时 API 路径。
2. 提取 `src/main/java/com/jimuqu/solon/claw` 下 `@Mapping` 暴露的后端路径和 HTTP 方法。
3. 将前端动态段归一化，例如 `${encodeURIComponent(id)}` 对齐后端 `{id}`。
4. 对脚本未完全展开的 helper 路径进行人工复核，例如 `sessionPath()`、`checkpointPath()`、`mcpServerPath()`、`encodeJobPath()`。
5. 并行只读复核前端调用面与后端路由面，确认机器候选是否真实。

## 前端调用面复核结果

当前前端运行时 API 都能找到后端对应入口，覆盖范围如下：

| 前端能力域 | 前端 API 包装 | 后端对应入口 |
| --- | --- | --- |
| 会话、搜索、检查点 | `sessions.ts` | `DashboardSessionController`、`DashboardSearchController` |
| 聊天上传与运行事件 | `chat.ts` | `DashboardChatController` |
| 诊断、审批、平台工具集 | `diagnostics.ts` | `DashboardDiagnosticsController`、`DashboardApprovalEventsController`、`DashboardPlatformToolsetsController` |
| 配置与工作区配置 | `config.ts` | `DashboardConfigController`、`DashboardRuntimeConfigController` |
| 国内渠道二维码 setup | `config.ts` | `DashboardGatewayController` |
| 工作区文件与下载 | `files.ts`、`download.ts` | `DashboardWorkspaceController` |
| 技能与记忆文件 | `skills.ts` | `DashboardSkillsController`、`DashboardWorkspaceController` |
| 运行记录与子代理 | `runs.ts` | `DashboardRunController` |
| 媒体缓存 | `media.ts` | `DashboardMediaController` |
| MCP 管理 | `mcp.ts` | `DashboardMcpController` |
| 定时任务 | `jobs.ts` | `DashboardCronController` |
| 模型与提供方 | `system.ts` | `DashboardProviderController`、`DashboardStatusController` |
| Agent 配置 | `agents.ts` | `DashboardAgentController` |
| 日志、洞察、用量 | `logs.ts`、`insights.ts`、`usage.ts` | `DashboardLogsController`、`DashboardInsightsController`、`DashboardAnalyticsController` |
| 人格日记 | `persona.ts` | `DashboardWorkspaceController` |
| TUI runtime 只读入口 | `tuiRuntime.ts` | `DashboardTuiRuntimeController` |

## 机器初筛候选与结论

| 初筛候选 | 结论 | 原因 |
| --- | --- | --- |
| `/api/gateway/setup/${platform}/qr` | 误报 | 后端显式暴露 `/api/gateway/setup/weixin/qr`、`/api/gateway/setup/feishu/qr`、`/api/gateway/setup/dingtalk/qr`，前端 `ChannelQrPlatform` 当前只会传这些受控平台。 |
| `/api/gateway/setup/${platform}/qr/${ticket}` | 误报 | 后端为微信、飞书、钉钉分别暴露轮询路径，前端动态段是受控平台枚举和二维码 ticket。 |
| `/api/curator/${action}` | 误报 | 前端 `action` 类型限制为 `apply | ignore`，后端存在 `/api/curator/apply` 和 `/api/curator/ignore`。 |
| `/api/checkpoints/${id}` | 误报 | 该 helper 只作为路径前缀，实际请求追加 `/preview` 或 `/rollback`，后端均存在。 |
| `/api/mcp/${serverId}` | 误报 | 该 helper 只作为 MCP 单服务路径前缀，实际请求追加 check、connect、reload、OAuth 子路径或 DELETE，后端均存在。 |
| `/api/cron/jobs/${id}` | 误报 | `encodeJobPath()` 生成的详情、更新、删除、inspect、pause、resume、trigger、retry、runs 路径均由 `DashboardCronController` 暴露。 |

## 已确认不是阶段 2.2 缺口的项

- `/api/gateway/doctor`：当前前端不再调用，统一诊断入口为 `/api/diagnostics/doctor`。
- `/api/sessions/search`：当前前端会话搜索调用 `/api/search`。
- `/api/todos`：不是 Web 前端运行时接口，属于后端测试中的不存在接口断言。
- `/api/tui/handshake`：TUI 客户端专用接口；Web 入口使用只读 `/api/tui/rpc` 查询 runtime 状态。
- `saveWeixinCredentials()`、`codex-auth.ts`、`nous-auth.ts` 中的未开放错误：当前没有接入页面真实提交路径，本阶段不补后端登录/凭证接口，避免扩大认证和凭据写入面。

## 当前结论

本轮未发现新的“前端已有但后端完全没有”的真实功能缺口。阶段 2.2 以审计记录收口，不新增后端接口。

## 验证

- 已脚本化比对前端 API 字面量、模板路径与后端 `@Mapping` 路径。
- 已人工复核动态 helper：`sessionPath()`、`checkpointPath()`、`mcpServerPath()`、`encodeJobPath()`、二维码 setup 平台路径。
- 已并行只读复核前端调用面和后端路由面，两个复核结果均未确认真实缺口。

## 剩余风险

- 本结论只覆盖当前源码中的 Web Dashboard 调用面；后续新增 API 包装或页面直接 `dashboardFetch` 时，需要重新运行同类比对。
- 本阶段没有修改生产代码，因此不覆盖接口返回结构、字段语义或运行时数据正确性，这些继续归属后续功能一致性和 UI/UX 阶段。
