# 项目原子功能 Bug 报告

日期：2026-07-17
基线：`f274d8399` 及阶段 0 文档提交
分支：`work/comprehensive-repair-20260717`

## 审计结论

本轮按单个功能点追踪后端、Web、Terminal UI、测试与 CI 的真实调用链，共确认 22 个可执行问题：

- 严重：2 个
- 高：11 个
- 中：9 个

旧报告中关于插件、多模态、语音、浏览器、价格和 Profile 删除的结论已经过时，不在本报告重复立项。UI 增强、AI 深度和前后端能力补齐分别留给阶段 2、4、5，不混入功能 Bug。

## 严重问题

### BUG-001：任意网页可连接本机 TUI WebSocket 控制面

- 位置：`DashboardAuthService.java:111-124`、`TerminalUiWebSocketListener.java:268-271,355+`
- 触发：用户浏览恶意网页，网页连接 `ws://127.0.0.1:<port>/ws/tui`。
- 根因：WebSocket 连接只因 TCP 远端为 loopback 就免鉴权，未校验 token 或可信 Origin；浏览器跨源连接的 TCP 远端仍是 loopback。
- 影响：攻击页面可访问配置、会话、Agent 和 Shell 等高权限 RPC。
- 修复要求：TUI WebSocket 必须校验 token；如保留本机免 token，只能基于精确 Origin 允许列表，不能仅信任远端地址。

### BUG-002：通配监听和 DNS-loopback Origin 可泄露或抢占 Dashboard token

- 位置：`DashboardAuthService.java:280-324`、`TerminalUiController.java:39-47`、`DashboardRuntimeConfigController.java:59-79`、`DashboardAuthFilter.java:32-59,88-96`
- 触发：Dashboard 使用 `0.0.0.0` 或 `::` 监听，或攻击者域名当前解析到 `127.0.0.1`。
- 根因：监听地址被错误当作浏览器 Origin 白名单，任意解析到 loopback 的域名也被视为可信本地 Origin。
- 影响：攻击网页可能读取现有 token；首次空 token 场景还可能抢先设置 token。
- 修复要求：只允许精确同源和显式配置的完整 Origin；禁止通过 DNS 解析结果判定浏览器 Origin 可信。

## 高优先级问题

### BUG-003：并发配对请求会向用户返回已失效的配对码

- 位置：`GatewayAuthorizationService.java:269-305`、`SqliteGatewayPolicyRepository.java:413-470`
- 触发：同一未绑定用户并发发送两条消息。
- 根因：冷却检查、旧请求删除、新请求写入和限流记录不在同一事务；后一个事务会删除前一个已返回的 code。
- 影响：至少一个用户已收到的配对码必然无效，并发还能绕过冷却。
- 修复要求：在仓储事务内原子完成冷却占用和 pairing 请求替换，只返回最终保存的 code。

### BUG-004：运行中的 Cron 会覆盖用户刚执行的暂停

- 位置：`CronJobService.java:589-595`、`DefaultCronScheduler.java:850-866`、`SqliteCronJobRepository.java:190-212`
- 触发：任务执行期间用户在 Dashboard 或命令中暂停任务。
- 根因：执行结束后无条件写入运行开始时预计算的 `ACTIVE` 或 `COMPLETED`，仓储更新没有状态或版本 CAS。
- 影响：暂停操作先显示成功，任务结束后却恢复活动并继续调度。
- 修复要求：结果写回必须保留当前 `PAUSED`，或按运行令牌/版本做 CAS。

### BUG-005：SQLite 事务复位失败可永久泄漏全局连接锁

- 位置：`SqliteDatabase.java:58-69,118-143`、`SqliteSessionRepository.java:413-419,482-488,998-1004`
- 触发：连接失效或数据库异常导致 `setAutoCommit(true)` 抛错。
- 根因：三个 `finally` 块先复位 auto-commit 再 close，复位抛错后 close 不执行；代理 close 才负责释放全局锁。
- 影响：之后所有 SQLite 访问都可能永久阻塞。
- 修复要求：用嵌套 `finally` 保证 close 必定执行，优先复用现有事务支持方法。

### BUG-006：运行结束的全量 Session 保存会覆盖并发设置更新

- 位置：`SqliteSessionRepository.java:354-406`、`AgentRunSupervisor.java:1280-1284`、`DefaultSessionSettingsCommandHandler.java:53-82,142-157`
- 触发：长任务运行期间执行 `/model`、`/fast`、`/reasoning` 等设置命令。
- 根因：设置命令先做定向更新，旧 session 快照随后以 `insert or replace` 全字段保存。
- 影响：界面提示设置成功，但运行结束后配置被静默回滚。
- 修复要求：运行末尾只更新本轮拥有字段，或使用版本 CAS 合并最新设置，不能继续用旧快照覆盖全行。

### BUG-007：Profile 协作任务取消存在注册窗口

- 位置：`ProfileTaskCoordinator.java:103-110,152-156`、`DashboardProfileTaskService.java:90-97`
- 触发：取消请求恰好发生在任务 submit 后、`activeCalls.put` 前。
- 根因：Future 在提交执行器后才注册；取消先把数据库改为 `CANCELLED`，却可能取不到 Future。
- 影响：UI 已显示取消，目标 Profile 仍可能继续执行 Shell 或文件写入等不可撤销副作用。
- 修复要求：先注册 `FutureTask` 再提交，或用取消标记使注册与取消线性化。

### BUG-008：Web 聊天缓存跨账号和服务端泄露

- 位置：`web/src/stores/solonclaw/chat.ts:198-210,515-530`、`web/src/api/sessionAuth.ts:40-50`
- 触发：退出后换账号登录，或切换后端地址。
- 根因：localStorage 使用固定 key，恢复发生在服务端校验前；清 token 和切 server 不清缓存。
- 影响：新账号可短暂或长期看到旧账号会话与消息，网络失败时旧内容持续保留。
- 修复要求：缓存按认证主体和服务端隔离，并在 logout、401、server change 时清除不再可信的缓存。

### BUG-009：Profile 切换只重建组件，不重置全局 Pinia 状态

- 位置：`web/src/App.vue:105`、`ChatView.vue:50-76`、`files.ts:22-66`、`JobsView.vue:148-151`
- 触发：Profile A 打开聊天、文件编辑器或任务列表后切换到 Profile B。
- 根因：组件 remount 不会清理 Pinia Store；页面 mounted 逻辑又会沿用已加载状态或保留编辑器。
- 影响：可能把 A 的内容保存到 B、对 B 发送 A 的 job id，或继续操作 A 的活动会话。
- 修复要求：Profile 切换时统一 reset 所有 Profile 相关 Store，或使用 profile-keyed state；保存和危险操作前再次校验 Profile。

### BUG-010：Persona 文件异步响应可串页并写错文件

- 位置：`web/src/views/solonclaw/PersonaFileView.vue:23-33,46-50`
- 触发：快速从 `agents` 切到 `soul`，前一个慢请求后返回，再点击保存。
- 根因：加载响应没有 file key 或请求代际校验，保存却使用当前 key 和可能来自旧请求的正文。
- 影响：旧文件内容可被保存到新文件。
- 修复要求：请求绑定 file key/序号，只有仍匹配当前页面时才能写状态；切换时清空旧编辑内容。

### BUG-011：Runs 页面请求竞态可显示并控制错误运行

- 位置：`web/src/views/solonclaw/RunsView.vue:88-123,136-157,222-229`
- 触发：快速切换会话或运行，旧请求晚于新请求返回。
- 根因：多个 await 前后读取可变的 `selectedSessionId` / `selectedRunId`，没有请求代际校验；失败时还保留上一份详情。
- 影响：页面标题是 B，事件和 selectedRun 却来自 A，停止/取消可能操作错误运行。
- 修复要求：捕获请求目标并校验序号，成功后原子替换；失败时清除或明确标记 stale 详情。

### BUG-012：MCP OAuth 异步响应可污染当前 Server

- 位置：`web/src/views/solonclaw/McpView.vue:119-124,268-333`
- 触发：OAuth 探测过程中快速切换 MCP Server。
- 根因：响应未校验发起时的 server id，后续提交使用当前 server id 和旧 server 表单数据。
- 影响：OAuth state、endpoint 或凭据流程可能错配到另一个 Server。
- 修复要求：所有 OAuth 请求绑定 server id 和请求代际，切换时作废旧响应。

### BUG-013：TUI 轮询持续进入串行 RPC 队列导致操作饥饿

- 位置：`terminal-ui/src/gatewayClient.ts:30,739-765`、`useMainApp.ts:292-402,521-556`、`useConfigSync.ts:261-286`
- 触发：任一 RPC 变慢，active list 和 config 等轮询仍按 1.5 秒或 5 秒继续排队。
- 根因：后端要求 RPC 串行，但轮询没有 single-flight、合并或过期结果保护，单请求超时可达 120 秒。
- 影响：连接恢复后用户命令仍排在大量旧轮询之后，TUI 长期无响应或状态倒退。
- 修复要求：各轮询入口做 single-flight/coalesce，并忽略过期响应；不能破坏后端串行约束。

## 中优先级问题

### BUG-014：`agent_manage` 是可配置但没有函数的幽灵工具集

- 位置：`AgentRuntimePolicy.java:23`、`DefaultToolRegistry.java:710`、`ToolRegistryExposureTest.java:81,132`
- 触发：为 Agent 选择 `agent_manage` 或 `all` 工具集。
- 根因：策略目录声明工具集，注册表对应分支为空，测试还固化了“名称存在但工具不存在”。
- 影响：配置成功但 Agent 无任何 Agent 管理函数，误导用户并破坏全局操作能力契约。
- 修复要求：实现真实、受审批的领域工具，或删除虚假能力；本轮目标要求全局操作，因此应实现。

### BUG-015：`spawn_tree.save` mutation 被错误路由为 list query

- 位置：`createGatewayEventHandler.ts:92-120`、`TerminalUiWebSocketListener.java:663-666`、`TerminalUiRpcService.java:2286-2294`
- 触发：TUI 上传包含 finished time、label、session、subagents 的完整 spawn tree。
- 根因：后端把 `spawn_tree.save` 和 `spawn_tree.list` 都调用同一个 list handler，完全忽略 save payload。
- 影响：接口宣称保存但实际不保存；仓储重放的 tools、notes、toolCount 又与上传快照不等价。
- 修复要求：删除虚假 save 契约并明确使用运行仓储，或真正持久化完整快照；必须增加 JSON-RPC 契约测试。

### BUG-016：401 登录跳转丢失原始目标页

- 位置：`web/src/api/client.ts`
- 触发：用户在任意 Dashboard 深层页面收到 401。
- 根因：跳登录页时没有携带当前 route redirect。
- 影响：重新登录后固定回到聊天页，用户丢失工作上下文。
- 修复要求：保存受控的站内目标并在登录成功后恢复，禁止开放重定向。

### BUG-017：已删除 Memory 页面仍有孤儿测试

- 位置：`web/tests/memoryLoadFailureStatic.test.ts:4-12`
- 触发：直接运行该测试。
- 根因：页面在 `4c756f3` 删除，测试没有同步删除，也未注册到 package script。
- 影响：测试稳定以 `ENOENT` 失败，却长期逃逸常规门禁。
- 修复要求：删除失效测试；当前 persona 页面已有各自测试，不恢复死页面。

### BUG-018：Profile UI 测试仍要求已删除的终端初始化契约

- 位置：`web/tests/profileUiStatic.test.ts:35-78`
- 触发：`npm run test:profiles`。
- 根因：后端/API/UI 已删除 `fetchProfileSetupCommand` 等能力，静态测试仍断言它们存在。
- 影响：Web 测试门禁稳定失败，无法提供可信基线。
- 修复要求：按当前产品契约更新测试，不能为过时测试恢复已删除接口。

### BUG-019：TUI resize 测试与终端分支契约不一致

- 位置：`terminal-ui/packages/solonclaw-ink/src/ink/ink-resize.test.ts:49`、`ink.tsx:970-973`
- 触发：`npm test` 或单独运行 resize 测试。
- 根因：测试无条件要求 Apple Terminal 的 `CSI 3J` 深清；默认实现正确输出 `CSI 2J + CSI H`。
- 影响：Terminal UI 全量测试稳定失败。
- 修复要求：测试分别覆盖默认终端与 Apple Terminal 分支。

### BUG-020：Terminal UI lint 存在 4 个确定性错误

- 位置：`terminal-ui/src/components/channelSetup.tsx:137,143,160,165`
- 触发：`npm run lint`。
- 根因：单行条件分支未遵守当前 `curly` 规则。
- 影响：lint 门禁失败，掩盖后续新增问题。
- 修复要求：按现有风格补花括号，不关闭规则。

### BUG-021：未使用的 catch 变量逃逸现有门禁

- 位置：`terminal-ui/src/gatewayClient.ts:338`
- 触发：TypeScript-aware unused 扫描。
- 根因：错误日志刻意使用固定脱敏信息，但仍保留未读取的 `catch (err)` 绑定；现有扫描范围没有发现它。
- 影响：产生真实未使用变量，说明当前未使用门禁覆盖不完整。
- 修复要求：改为 `catch {}`，并补齐覆盖实际源码的未使用检查，不用 `_err` 或规则屏蔽。

### BUG-022：CI 没有真实测试门禁

- 位置：`.github/workflows/naming.yml`、`.github/workflows/packages.yml`、`.github/workflows/release.yml:112-113`
- 触发：任意只通过编译和命名检查、但破坏行为测试的提交。
- 根因：工作流只跑守卫脚本，release 明确使用 `mvn -B -DskipTests package`。
- 影响：本报告中的三个稳定失败测试和 lint 错误长期未被 CI 发现。
- 修复要求：增加独立、稳定的后端/Web/Terminal UI 测试工作流；不把 live integration 的凭据跳过当失败。

## 当前验证基线

- 后端：`mvn -Dskip.web.build=true test` 通过，2414 tests，0 failures，0 errors，3 skipped。
- Web：`npm run build` 通过；44 个 `test:*` 脚本中仅 `test:profiles` 失败。
- Terminal UI：type-check 通过；全量测试 1132 passed、1 skipped、1 failed；lint 有 4 errors、7 warnings。
- 重复检测：40 行阈值无生产代码完全重复块；20 行阈值只命中合理样板和后续阶段候选。

## 修复顺序

1. 先修 BUG-001、BUG-002 两个控制面安全问题。
2. 再修 BUG-003 至 BUG-013 的并发、数据一致性和跨 Profile 状态问题。
3. 修复 BUG-017 至 BUG-021，恢复当前可执行门禁。
4. BUG-014、BUG-015 结合阶段 2 与阶段 4 的能力契约处理。
5. BUG-016、BUG-022 分别纳入 UI 连续性与持续工作流收口。
