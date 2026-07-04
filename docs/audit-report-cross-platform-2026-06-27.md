# 三端全面排查报告：BUG / 优化点 / 不一致问题

> 排查日期：2026-06-27
> 排查范围：后端（Java/Solon）、TUI（TypeScript/Ink）、WEB（Vue 3）
> 排查方式：6 个并行 Explore 代理 + 人工深度检查
> 代理分工：后端API/Controller · TUI终端界面 · WEB前端 · 三端一致性 · 后端命令与网关 · WebSocket实时通信

---

## 目录

- [第一部分：严重问题（必须修复）](#第一部分严重问题必须修复)
- [第二部分：高优先级问题](#第二部分高优先级问题)
- [第三部分：中优先级问题](#第三部分中优先级问题)
- [第四部分：低优先级优化](#第四部分低优先级优化)
- [附录A：三端Slash Command完整对比](#附录a三端slash-command完整对比)
- [附录B：后端API端点 vs WEB前端调用对比](#附录b后端api端点-vs-web前端调用对比)
- [附录C：问题统计](#附录c问题统计)

---

# 第一部分：严重问题（必须修复）

## S-01 🔴 后端 GatewayController 缺少 QQBOT 和 YUANBAO 平台白名单

- **文件**：`src/main/java/.../bootstrap/GatewayController.java` 第 92-101 行
- **问题**：`validateMessage()` 方法的 switch 语句仅包含 `MEMORY`, `FEISHU`, `DINGTALK`, `WECOM`, `WEIXIN`，但 `PlatformType` 枚举还定义了 `QQBOT` 和 `YUANBAO`。通过 HTTP 网关注入接口发送 QQBOT 或 YUANBAO 平台的消息会直接返回 400 错误。
- **影响**：两个平台的 HTTP 注入通道完全不可用
- **修复**：在 switch 中添加 `QQBOT` 和 `YUANBAO` 分支

## S-02 🔴 `/compact` 命令三端语义冲突

- **后端**（DefaultCommandService 第 1784 行）：`/compact` = 上下文压缩（调用 `contextCompressionService.compressNowWithOutcome`）
- **TUI**（core.ts 第 249 行）：`/compact` = 切换紧凑 transcript 显示模式（`patchUiState({ compact: next })`）
- **WEB**：无 `/compact` 支持
- **影响**：用户在 TUI 输入 `/compact` 以为在压缩上下文，实际上只是切换了显示模式。语义完全相反。

## S-03 🔴 TUI 缺少 `/approve`、`/deny`、`/cancel` 危险命令审批

- **后端**：有完整的危险命令审批流程（`DangerousCommandApprovalService`）
- **TUI**：完全没有实现这些命令
- **影响**：当 Agent 执行危险命令需要审批时，TUI 用户无法通过命令行批准或拒绝，Agent 运行会被阻塞

## S-04 🔴 TUI 缺少 `/pairing` 渠道配对管理

- **后端**：有完整的配对管理（`DefaultPairingCommandHandler`）：claim-admin、list、pending、approve、revoke、approved、clear-pending
- **TUI**：完全没有实现
- **影响**：TUI 用户无法管理渠道配对，无法授权新用户

## S-05 🔴 WEB i18n 只加载了中文 locale，其他 7 种语言全部失效

- **文件**：`web/src/i18n/index.ts`
- **问题**：只 import 了 `zh` 并注册到 `messages: { zh }`。项目中有 `en.ts`、`de.ts`、`es.ts`、`fr.ts`、`ja.ts`、`ko.ts`、`pt.ts` 共 7 个完整 locale 文件，但都没有被 import 和注册
- **影响**：用户切换到任何非中文语言时，所有文本显示为翻译 key

## S-06 🔴 WEB `fetchSession` 为获取单个会话详情却请求全部 500 条会话

- **文件**：`web/src/api/solonclaw/sessions.ts` 第 264-307 行
- **问题**：`fetchSession(id)` 内部使用 `Promise.all` 并行发起两个请求：`/api/sessions?limit=500&offset=0`（获取全部会话列表）+ 消息详情。仅为查找一个会话的摘要信息就拉取全部 500 条会话
- **影响**：每次打开会话详情都产生巨大网络开销

## S-07 🔴 WEB `listFiles` 对非空路径直接返回空数组，文件浏览器目录导航不可用

- **文件**：`web/src/api/solonclaw/files.ts` 第 53-56 行
- **问题**：`listFiles` 函数在 `path` 非空时直接返回 `{ entries: [], path }`，完全不调用 API
- **影响**：文件浏览器无法进入子目录，目录导航功能实质上是坏的

## S-08 🔴 WEB 缺少 404 兜底路由

- **文件**：`web/src/router/index.ts`
- **问题**：路由表中没有 `/:pathMatch(.*)*` 兜底路由
- **影响**：用户访问不存在的路径时页面显示空白

## S-09 🔴 WEB `v-html` 渲染用户可控内容存在 XSS 风险

- **文件**：`web/src/components/solonclaw/chat/MessageItem.vue` 第 220、224 行；`MarkdownRenderer.vue` 第 56 行
- **问题**：工具调用参数/结果通过 `v-html` 渲染，markdown-it 的 `linkify: true` 可能生成 `javascript:` 伪协议链接
- **影响**：安全隐患

## S-10 🔴 WEB settings store 引用了永远不存在的配置字段

- **文件**：`web/src/stores/solonclaw/settings.ts` 第 12-16、31-35 行
- **问题**：定义了 `telegram`、`discord`、`slack`、`whatsapp`、`matrix` 五个 ref，但 `fetchConfig()` 返回对象中根本没有这些字段。五个 ref 永远是空对象 `{}`
- **影响**：死数据，UI 可能显示异常

## S-11 🔴 后端 `server.ready` vs TUI `gateway.ready` 事件名不匹配

- **问题**：后端发送 `server.ready` 事件，但 TUI 监听的是 `gateway.ready`。TUI 初始化流程可能完全不触发
- **影响**：TUI 在 gateway 重启后可能无法正确恢复

## S-12 🔴 WEB SSE 仅覆盖约 6 种事件类型，TUI WebSocket 接收 20+

- **问题**：WEB SSE 事件处理器只处理 `message.delta`、`reasoning.delta`、`run.completed`、`run.failed` 等少量事件；TUI 通过 WebSocket 接收 20+ 种事件
- **影响**：WEB 用户实时反馈严重不足，缺少工具调用进度、审批请求、压缩通知等关键信息

---

# 第二部分：高优先级问题

## H-01 后端响应格式不一致（6 个 Controller）

以下 Controller 的端点直接返回原始类型，未使用 `DashboardResponse.ok()` 包装：

| Controller | 方法 | 返回类型 |
|-----------|------|---------|
| DashboardSkillsController | `skills()` | `List<Map>` |
| DashboardSkillsController | `files()` | `List<Map>` |
| DashboardCronController | `jobs()` | `List<Map>` |
| DashboardProviderController | `providers()` | `Map` |
| DashboardAnalyticsController | `usage()` | `Map` |
| DashboardLogsController | `logs()` | `Map` |

## H-02 后端 SSE `events()` 端点缺少异常处理

- **文件**：`DashboardChatController.java` 第 120-150 行
- **问题**：方法声明 `throws Exception` 但无 try-catch，SSE 连接中断时可能导致未捕获异常和信息泄露

## H-03 后端 CORS 直接反射 Origin 头，无白名单校验

- **文件**：`DashboardAuthFilter.java` 第 50-70 行
- **问题**：直接反射请求的 `Origin` 头到 `Access-Control-Allow-Origin`，未做白名单校验

## H-04 后端 Bearer Token 大小写敏感

- **文件**：`DashboardAuthService.java` 第 85-90 行
- **问题**：严格匹配 `"Bearer "`，客户端发送 `"bearer "` 或 `"BEARER "` 将认证失败。违反 RFC 7235

## H-05 后端默认弱口令 "admin" 无强制更换机制

- **文件**：`DashboardAuthService.java` 第 27 行
- **问题**：默认 token 为 "admin"，仅输出日志警告，无运行时强制

## H-06 后端 `GatewayInjectionAuthService.markNonce()` 超容量时淘汰未过期 nonce

- **文件**：`GatewayInjectionAuthService.java` 第 122-136 行
- **问题**：过期清理和容量淘汰使用 `||` 短路，高并发下合法 nonce 可能被提前淘汰，重放攻击检测失效

## H-07 后端 `ToolCallLoopGuardrailService` ThreadLocal 从未清理

- **文件**：`ToolCallLoopGuardrailService.java` 第 39 行
- **问题**：`OTHER_TOOL_CALL_EPOCH` ThreadLocal 在 `set()` 后全文无 `remove()` 调用，线程池复用时会携带过期值

## H-08 TUI 多个 slash 命令 RPC 调用缺少 `.catch()` 错误处理

以下命令的 RPC 调用网络失败时会产生未捕获的 Promise rejection：

| 命令 | 文件 | 说明 |
|------|------|------|
| `/undo` | core.ts:597 | `session.undo` 无 `.catch()` |
| `/retry` | core.ts:624 | `session.undo` 无 `.catch()` |
| `/usage` | session.ts:552 | `session.usage` 无 `.catch()` |
| `/model` | session.ts:103 | `config.set` 无 `.catch()` |
| `/personality` | session.ts:176 | `config.set` 无 `.catch()` |
| `/yolo` | session.ts:411 | `config.set` 无 `.catch()` |
| `/verbose` | session.ts:541 | `config.set` 无 `.catch()` |
| `/indicator` | session.ts:391 | `config.set` 无 `.catch()` |
| `/skin` | session.ts:364 | `config.set` 无 `.catch()` |
| `/voice` | session.ts:276 | `voice.toggle` 无 `.catch()` |

## H-09 TUI `createGatewayEventHandler` switch 无 `default` 分支

- **文件**：`terminal-ui/src/app/createGatewayEventHandler.ts` 第 383-858 行
- **问题**：未处理的事件类型被静默丢弃，无日志或警告

## H-10 TUI WebSocket 无连接级重连机制

- **文件**：`terminal-ui/src/gatewayClient.ts`
- **问题**：WebSocket 断开时重启整个 gateway 子进程，而非简单的重连。网络抖动的代价过大

## H-11 WEB 缺少 MemoryView 路由

- **文件**：`web/src/views/solonclaw/MemoryView.vue` 存在，但 `router/index.ts` 中未注册 `/solonclaw/memory` 路由

## H-12 WEB `visibilitychange` 监听器永远不会被清理

- **文件**：`web/src/stores/solonclaw/chat.ts` 第 1130-1138 行
- **问题**：Pinia store 的 `defineStore` 回调只执行一次，`addEventListener` 无法被清理，导致内存泄漏 + 每次切标签页产生不必要请求

## H-13 WEB `fetchSessions` 客户端过滤而非服务端过滤

- **文件**：`web/src/api/solonclaw/sessions.ts` 第 191-199 行
- **问题**：`source` 参数未作为查询参数传递给服务端，先请求全部会话再客户端过滤

## H-14 WEB 工具消息在流式结束后从主消息列表中消失

- **文件**：`web/src/components/solonclaw/chat/MessageList.vue` 第 15-17 行
- **问题**：`displayMessages` 过滤掉 `role === 'tool'` 的消息，工具调用面板在流式结束后也隐藏。用户无法回顾工具调用历史

## H-15 后端 DefaultCommandService 有 14+ 个构造函数重载

- **文件**：`DefaultCommandService.java`
- **问题**：典型的"伸缩构造函数"反模式，每个新增依赖都添加一个新的构造函数

## H-16 后端大量裸 Thread 创建，缺乏线程池管控

- **涉及文件**：`AgentRunSupervisor.java`、`DefaultGatewayService.java`、`GatewayRestartCoordinator.java`、`DefaultCronScheduler.java` 等 7 处
- **问题**：使用 `new Thread(runnable, name).start()` 模式，线程数不可控

---

# 第三部分：中优先级问题

## M-01 后端 4 个已注册命令未在 `handle()` 中实现

| 命令 | 注册分类 | 说明 |
|------|---------|------|
| `security` | security | 会走到 `registeredUnimplementedReply` |
| `config` | configuration | 同上 |
| `setup` | configuration | 同上 |
| `debug` | info | 同上 |

## M-02 后端 `Context.current()` 反模式（4 处）

- **文件**：`DashboardSessionController.java` 第 60、85、110、135 行
- **问题**：直接调用 `Context.current()` 而非通过方法参数注入，增加耦合度

## M-03 后端多个 Controller POST/PUT 端点缺少参数验证

- **涉及**：DashboardAgentController、DashboardConfigController、DashboardCronController
- **问题**：未验证必填字段、配置项合法性、cron 表达式格式

## M-04 后端 `isIncompleteJson()` 检测逻辑过于简单

- **文件**：`GatewayController.java` 第 127-131 行
- **问题**：仅检查首末字符配对，无法识别嵌套 JSON 中的合法截断

## M-05 后端 `DefaultGatewayService` 裸 Thread 创建

- **文件**：第 327、383 行
- **问题**：`jimuqu-goal-kickoff` 和 `jimuqu-goal-continuation` 线程未使用线程池

## M-06 后端 `DashboardAuthService.isAllowedDashboardOrigin` DNS 查询风险

- **文件**：第 291 行
- **问题**：`InetAddress.getByName(host).isLoopbackAddress()` 可被 DNS rebinding 攻击利用

## M-07 后端 `DefaultCronScheduler` ExecutorService 未关闭

- **文件**：第 1184 行附近
- **问题**：`newSingleThreadExecutor()` 在正常返回路径未调用 `shutdown()`

## M-08 后端异常信息泄露风险（3 处）

- **涉及**：DashboardConfigController、DashboardChatController、DashboardRunController
- **问题**：catch 块直接将 `e.getMessage()` 返回给客户端，可能包含敏感信息

## M-09 TUI `/setup` 命令 help 文本是中文，其他命令是英文

- **文件**：`setup.ts` 第 5-8 行
- **问题**：`setupUsage` 使用中文，与 `coreCommands`/`sessionCommands`/`opsCommands` 全英文风格不一致

## M-10 TUI nanostore `patchTurnState`/`patchUiState` 存在竞态条件

- **文件**：`turnStore.ts` 第 38-39 行、`uiStore.ts` 第 42-43 行
- **问题**：函数形式的 `get()` + `set()` 在同一微任务中可能被交错执行

## M-11 TUI `voice.transcript` 事件使用 `setTimeout` 延迟提交

- **文件**：`createGatewayEventHandler.ts` 第 546-547 行
- **问题**：依赖 React 在下一个事件循环 tick 之前完成状态更新的假设

## M-12 TUI 配置同步自动 reload MCP

- **文件**：`useConfigSync.ts` 第 279-281 行
- **问题**：检测到配置文件变化时自动发送 `reload.mcp`，导致提示缓存失效和 MCP 工具暂时不可用

## M-13 WEB SSE 解析中 `JSON.parse` 无 try-catch 保护

- **文件**：`web/src/api/solonclaw/chat.ts` 第 164 行
- **问题**：非 JSON 格式的 SSE data 会导致整个流中断

## M-14 WEB `chat store` 体积过大（1164 行），职责过多

- **文件**：`web/src/stores/solonclaw/chat.ts`
- **问题**：单个 store 包含会话管理、消息缓存、SSE 流处理、轮询恢复等所有逻辑

## M-15 WEB 聊天 store 中 SSE 事件处理器直接修改数组元素属性

- **文件**：第 889-890、907-908 行
- **问题**：`last.content += evt.delta` 绕过 `updateMessage` 函数，且频繁字符串拼接性能差

## M-16 WEB ChatPanel 15 秒被动刷新定时器过于频繁

- **文件**：`ChatPanel.vue` 第 32、84 行
- **问题**：每 15 秒调用 `fetchSession(sid)`，不活跃用户产生大量不必要网络流量

## M-17 WEB `visibilitychange` 监听重复（ChatPanel + chat store）

- **问题**：两处都监听并调用 `refreshActiveSession()`，切标签页时触发两次刷新

## M-18 WEB 来源标签和 SSE 系统消息硬编码中文

- **文件**：`session-display.ts`、`chat.ts` 第 848、859、870、880 行
- **问题**：不跟随语言设置，英文界面下仍显示中文

## M-19 WEB `searchSessions` 搜索后又拉取全部会话列表做关联

- **文件**：`sessions.ts` 第 201-254 行
- **问题**：双重网络开销

## M-20 WEB `saveCredentials` 逐条发送 N 个独立 API 请求

- **文件**：`config.ts` 第 230-243 行
- **问题**：每个配置项一次 HTTP 请求，应合并为批量写入

## M-21 WEB localStorage 缓存策略可能导致配额溢出

- **文件**：`chat.ts` 第 163-176 行
- **问题**：每个会话消息独立缓存，大量会话易突破 5-10MB 限制

## M-22 WEB 轮询间隔过短（2 秒）

- **文件**：`chat.ts` 第 166 行
- **问题**：`POLL_INTERVAL_MS = 2000`，断线恢复期间每 2 秒产生 2 个 HTTP 请求

---

# 第四部分：低优先级优化

## L-01 后端 `DashboardProviderController` 方法命名不规范

- `JimuquModels()` 和 `JimuquModelHealth()` 应改为小写开头

## L-02 后端配置字段缺少验证注解

- `AppConfig.java` 中的配置字段缺少 `@NotBlank`、`@Min` 等验证

## L-03 后端 `CronJobService.PROTECTED_CRON_DISABLED_TOOLSETS` 是公开可变 List

- 已使用 `Collections.unmodifiableList()` 包装，并补充回归测试防止外部改写定时任务禁用工具集。

## L-04 后端 `CommandRegistry` 别名存在覆盖风险

- 已补注册期冲突检测；两个不同命令声明相同别名时会快速失败，避免静默覆盖 slash command 解析。

## L-05 后端 `DashboardAuthFilter` OPTIONS 仅对 `/api/` 返回 204

- 已改为所有 OPTIONS 预检统一返回 204，避免静态资源或页面路由链误处理 CORS 预检。

## L-06 后端 WebSocket token 支持 query 参数明文传输

- URL 中的 token 会出现在日志和浏览器历史中

## L-07 TUI `undo`/`retry` 缺少 null 响应检查

- `r.removed` 访问前未检查 `r` 是否为 null

## L-08 TUI `help` 命令硬编码 TUI 部分未使用 `usage` 字段

- 修改命令参数时需同步修改两处

## L-09 TUI `handleWebSocketFrame` 静默丢弃解析错误

- JSON 解析失败时只记录日志，不通知调用者

## L-10 TUI `turnController` 是单例，缺少 session 隔离

- 切换 session 时流式状态不会自动保存/恢复

## L-11 TUI `writeActiveSessionFile` 使用 `writeFileSync` 阻塞主线程

- 高频 session 切换场景下可能造成微小 UI 卡顿

## L-12 WEB `fetchModels()` 返回空数据（stub）

- `chat.ts` 第 184-186 行，永远返回 `{ data: [] }`

## L-13 WEB `nous-auth.ts` 和 `codex-auth.ts` 是抛出错误的桩实现

- `startLogin` 直接 `throw new Error()`

## L-14 WEB `uid()` 生成的 ID 碰撞风险

- 已优先使用 `crypto.randomUUID()` 生成聊天消息 ID，并为无 Web Crypto 环境补充模块级计数器兜底。

## L-15 WEB 密码登录 i18n 有但 UI 无

- `zh.ts` 中有完整的密码登录 i18n 键，但 `LoginView.vue` 模板中只有令牌登录

## L-16 WEB `main.ts` token 提取逻辑复杂且脆弱

- 从 URL search params 和 hash params 两处提取 token，无错误处理

## L-17 WEB `useTheme` 在模块顶层注册 `mediaQuery` 监听器

- 不在任何 Vue 生命周期内，无法清理

---

# 附录A：三端 Slash Command 完整对比

## 后端注册但 TUI/WEB 均缺失的命令

| 命令 | 后端 | TUI | WEB | 说明 |
|------|------|-----|-----|------|
| `/whoami` | ✅ | ❌ | ❌ | 查看 slash 命令访问身份 |
| `/insights` | ✅ | ❌ | ✅ | 使用洞察（WEB 有页面） |
| `/debug` | ✅ | ❌ | ❌ | 脱敏调试诊断 |
| `/security` | ✅ | ❌ | ❌ | 安全策略查看 |
| `/version` | ✅ | ❌ | ❌ | 版本信息 |
| `/config` | ✅ | ❌ | ❌ | 本地配置查看/写入 |
| `/toolsets` | ✅ | ❌ | ❌ | 工具集列表 |
| `/plugins` | ✅ | ❌ | ❌ | 插件状态 |
| `/cron` | ✅ | ❌ | ✅ | 定时任务（WEB 有 JobsView） |
| `/goal` | ✅ | ❌ | ❌ | 跨轮长目标 |
| `/recap` | ✅ | ❌ | ❌ | 会话摘要 |
| `/trajectory` | ✅ | ❌ | ❌ | 会话导出 |
| `/proactive` | ✅ | ❌ | ❌ | 主动协作控制 |
| `/sethome` | ✅ | ❌ | ❌ | home channel 设置 |
| `/pairing` | ✅ | ❌ | ❌ | 渠道配对管理 |
| `/approve` | ✅ | ❌ | ❌ | 批准危险命令 |
| `/deny` | ✅ | ❌ | ❌ | 拒绝危险命令 |
| `/cancel` | ✅ | ❌ | ❌ | 取消审批 |
| `/confirm` | ✅ | ❌ | ❌ | 确认 slash 命令 |
| `/platforms` | ✅ | ❌ | ❌ | 平台状态查看 |
| `/agent` | ✅ | ❌ | ✅ | Agent 切换（WEB 有组件） |
| `/rollback` | ✅ | ✅ | ❌ | checkpoint 回滚 |

## TUI 特有命令（后端 CommandRegistry 中未注册或仅 terminal scope）

`/mouse`、`/redraw`、`/save`、`/indicator`、`/yolo`、`/verbose`、`/terminal-setup`、`/fortune`、`/details`、`/heapdump`、`/mem`、`/replay`、`/replay-diff`

---

# 附录B：后端 API 端点 vs WEB 前端调用对比

## 后端有但 WEB 前端未调用的端点

| 后端端点 | 说明 |
|---------|------|
| `/api/cron/jobs/{id}/inspect` | 任务检查 |
| `/api/cron/jobs/status` | 任务状态 |
| `/api/cron/jobs/next` | 下次执行时间 |
| `/api/config/defaults` | 默认配置 |
| `/api/config/schema` | 配置 Schema |
| `/api/config/diagnostics` | 配置诊断 |
| `/api/models` | 模型列表 |
| `/api/runs/subagents/active` | 活跃子 Agent |
| `/api/search` | 全局搜索 |
| `/api/sessions/{id}/trajectory/save` | 保存 trajectory |
| `/api/tools/toolsets` | 工具集列表 |
| `/api/workspace/files/{key}/restore` | 文件恢复 |

## WEB 侧边栏 vs 路由映射

| 侧边栏项 | 路由 | 状态 |
|---------|------|------|
| 对话 | `/solonclaw/chat` | ✅ |
| 智能体 | `/solonclaw/agents` | ✅ |
| 任务 | `/solonclaw/jobs` | ✅ |
| 模型 | `/solonclaw/models` | ✅ |
| 技能 | `/solonclaw/skills` | ✅ |
| **记忆** | **❌ 无路由** | **🔴 缺失** |
| 日志 | `/solonclaw/logs` | ✅ |
| 用量 | `/solonclaw/usage` | ✅ |
| 渠道 | `/solonclaw/channels` | ✅ |
| 网关 | `/solonclaw/gateways` | ✅ |
| 文件 | `/solonclaw/files` | ✅ |
| 系统诊断 | `/solonclaw/diagnostics` | ✅ |
| 技能维护 | `/solonclaw/curator` | ✅ |
| 工具接入 | `/solonclaw/mcp` | ✅ |
| 设置 | `/solonclaw/settings` | ✅ |
| 运行记录 | `/solonclaw/runs` | ✅ |

---

# 附录C：问题统计

| 严重程度 | 数量 | 说明 |
|---------|------|------|
| 🔴 严重 (S) | 12 | 功能 BUG、安全漏洞、数据丢失风险 |
| 🟠 高 (H) | 16 | 重要功能缺失、性能瓶颈、代码质量 |
| 🟡 中 (M) | 22 | 不一致、优化点、潜在风险 |
| 🟢 低 (L) | 17 | 代码风格、防御性编程、小优化 |
| **合计** | **67** | |

## 建议优先修复顺序

1. **S-01** GatewayController 补充 QQBOT/YUANBAO — 一行代码修复
2. **S-02** `/compact` 语义冲突 — 需要统一命名
3. **S-03** TUI 缺少审批命令 — 安全阻塞
4. **S-04** TUI 缺少 pairing 命令 — 功能阻塞
5. **S-05** WEB i18n locale 未注册 — 修复成本极低
6. **S-06** WEB fetchSession 性能问题 — 影响所有用户
7. **S-07** WEB 文件浏览器目录导航不可用
8. **S-08** WEB 404 兜底路由缺失
9. **S-09** WEB XSS 风险
10. **S-10** WEB settings store 死数据
11. **S-11** 后端/TUI 事件名不匹配
12. **S-12** WEB SSE 事件覆盖不足

---

*报告生成时间：2026-06-27*
*排查工具：6 个并行 Explore 代理 + 人工深度检查*
*总计发现：67 个问题（12 严重 + 16 高 + 22 中 + 17 低）*
