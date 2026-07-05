# 无人值守全面修复阶段 1.1 增量缺陷报告

生成时间：2026-07-05

## 对应外部对标能力点

- Dashboard-first setup / doctor：登录前后不应触发无意义的模型接口错误，模型与运行状态应在授权后稳定刷新。
- 本地 CLI / TUI 交互层：后端离线、重连、输入、审计和退出路径应保持可控，不应刷屏或快速耗尽内存。
- 工具系统与 Agent 主循环：工具循环状态必须按调用上下文清理，避免一次工具调用污染后续请求。

## 审计范围

本报告追加记录 2026-07-05 无人值守 E2E 与 focused 测试确认的问题。已在旧报告中闭环的 BUG-001 至 BUG-024 不重复列入。

## BUG-025：TUI 后端离线时握手与普通 RPC 互相触发重连导致刷屏和快速内存增长

状态：已修复，提交 `c9f83b51c`

影响范围：

- Node TUI 离线启动。
- 后端端口错误、后端进程退出或网络断开后的输入体验。
- 终端活动流、转写消息和网关日志体积。

当前事实：

- 真实 TTY 复测中，将 `SOLONCLAW_SERVER_URL` 指向不可达端口后，旧路径会反复输出 `gateway not connected: config.get`。
- 早期复现中出现快速堆内存增长，监测到 heap 约 1.5GB、rss 约 1.7GB。
- 修复后同一路径运行约 50 秒，`transport_exit=1`，未再出现 memory warning。

根因：

- 后端握手失败后，普通 RPC 仍会重新触发后端握手。
- 断线事件和转写消息没有对重复离线状态做去重，用户界面和日志会被相同状态消息淹没。

处理记录：

- `GatewayClient` 在后端握手失败后标记 `backendUnavailable`，普通 RPC 不再自动重启握手。
- 保留显式 `gw.start()` 的重新连接能力。
- 活动消息和转写消息增加重复状态去重，离线提示保持稳定。

验证命令：

```bash
npm --prefix terminal-ui test -- src/__tests__/gatewayClient.test.ts src/lib/messages.test.ts src/__tests__/createGatewayEventHandler.test.ts
npm --prefix terminal-ui run type-check
npm --prefix terminal-ui run build
```

真实 TTY 复核：

```bash
SOLONCLAW_SERVER_URL=http://127.0.0.1:65535
SOLONCLAW_HOME=%TEMP%\solonclaw-tui-offline-verify-fix2
npm start
```

## BUG-026：Dashboard 登录页加载阶段提前请求模型接口导致控制台 401

状态：已修复，提交 `9c54910dc`

影响范围：

- Dashboard 登录页。
- 首次打开 Web UI 时的浏览器控制台。
- 未登录状态下的运行时模型和健康状态同步。

当前事实：

- Web E2E 打开登录页时，页面尚未登录就请求 `/api/providers`。
- 后端按预期返回 401，但用户视角会看到无意义的控制台错误。
- 登录后模型页和模型配置路径可正常展示，说明问题不在模型配置本身。

根因：

- `App.vue` 在 router 完成初始解析前就启动 runtime model/health 同步。
- 登录页判断依赖路由状态，初始路由未 ready 时会误把登录页当成可同步页面。

处理记录：

- `web/src/App.vue` 等待 `router.isReady()` 后再启动应用运行时同步。
- `syncAppRuntime` 监听 `isLoginPage` 和 `ready`，登录页或 router 未 ready 时不请求模型接口。
- 静态测试覆盖登录页初始化不触发运行时模型同步。

验证命令：

```bash
node --experimental-strip-types web/tests/appHealthPollingAfterLoginStatic.test.ts
npm --prefix web run build
```

## BUG-027：启动时模型提供方配置被默认值覆盖

状态：已修复，提交 `00bb4af53`

影响范围：

- `java -jar` 启动时通过系统属性或环境注入的模型提供方配置。
- Dashboard 模型页显示的默认 provider、base URL、dialect 与 default model。
- Web/TUI 使用真实模型做 E2E 时的启动可靠性。

当前事实：

- E2E 启动参数中已传入默认 provider、base URL、API key、dialect 和模型。
- Dashboard 模型页应展示启动注入的 provider/model，而不是被默认配置覆盖。

根因：

- 配置加载阶段会用默认配置覆盖已注入的 provider 细节。
- 真实 E2E 依赖启动参数时，该问题会让前端看到的模型状态与启动配置不一致。

处理记录：

- `AppConfigLoader` 保留启动阶段已有的模型提供方配置。
- `AppConfigProviderLoadTest` 覆盖启动 provider 配置不会被默认值覆盖。

验证命令：

```bash
mvn "-Dskip.web.build=true" "-Dtest=AppConfigProviderLoadTest" test
```

## BUG-028：工具循环防护线程状态未清理，可能污染后续工具调用

状态：已修复，提交 `95ecb63be`

影响范围：

- Agent 主循环中的工具调用。
- 同线程复用执行多个请求时的循环防护判断。
- 长时间运行的 Web/TUI 会话。

当前事实：

- 工具循环防护服务在一次工具调用结束后需要清理线程本地状态。
- 如果不清理，同线程后续请求可能继承上一轮工具调用上下文。

根因：

- 工具循环状态绑定在线程上下文，但原路径没有在调用结束时统一释放。

处理记录：

- `ToolCallLoopGuardrailService` 在工具调用结束后清理线程状态。
- 回归测试覆盖状态清理，避免一次调用影响下一次判断。

验证命令：

```bash
mvn "-Dskip.web.build=true" "-Dtest=ToolCallLoopGuardrailServiceTest" test
```

## BUG-029：终端审计脚本与 TUI 重连验证路径不稳定

状态：已修复，提交 `041ec41a0`

影响范围：

- `scripts/audit-terminal-commands.py` 终端审计。
- Windows 环境下的 TUI 重连回归复核。
- 无人值守流程中的终端质量门禁。

当前事实：

- 终端审计和 TUI 重连验证需要在 Windows 环境下稳定表达“不支持 PTY”或“后端离线”，不能误进入模型启动或刷屏路径。
- 相关自测需要覆盖 Windows/非 PTY 环境的行为边界。

处理记录：

- 终端审计脚本补充 Windows/PTY 边界处理。
- `gatewayClient` 增加重连状态相关测试。
- 自测脚本补充对应断言。

验证命令：

```bash
python scripts/audit-terminal-commands.selftest.py
npm --prefix terminal-ui test -- src/__tests__/gatewayClient.test.ts
```

## BUG-030：真实模型 E2E 返回 401 Invalid API Key

状态：待复核，疑似外部凭据或上游服务状态问题

影响范围：

- Web Dashboard 真实聊天路径。
- TUI 真实模型会话路径。
- 使用真实 provider 的无人值守 E2E。

当前事实：

- Web E2E 中，Dashboard 登录、模型页和 SSE 请求路径可达。
- 真实模型请求返回 `401 Invalid API Key`。
- 后端 provider/model 路径已确认能按启动参数进入请求链路。

下一步：

- 不在仓库文档中记录密钥值。
- 后续复测时只记录 provider 名称、协议、模型名、HTTP 状态和脱敏错误。
- 若同一密钥仍返回 401，应作为外部凭据或服务状态问题单独处理，不阻塞本地 UI/协议路径修复。

## BUG-031：浏览器本地缓存的后端地址可能让 Dashboard 指向旧服务

状态：已修复（2026-07-05）

影响范围：

- Dashboard 本地开发与 E2E 测试。
- 频繁切换后端端口或临时工作区的用户。

当前事实：

- Web E2E 观察到浏览器 localStorage 中残留的 `solonclaw_server_url` 可能让 UI 指向旧后端。
- 该问题会造成“当前后端已启动但页面仍访问旧地址”的假失败。

修复内容：

- `getBaseUrlValue()` 对本机 Dashboard 上残留的 loopback 后端地址回退到相对地址，继续走当前页面的 `/api` 入口与 Vite 代理。
- 非本机远程后端地址仍保留原有可配置行为。

验证命令：

```bash
node --experimental-strip-types web/tests/sessionAuthClearInjectedToken.test.ts
npm --prefix web run build
```

## BUG-032：Dashboard 暗色侧栏“系统”分组展开后子菜单文字几乎不可读

状态：已修复（2026-07-05）

影响范围：

- Dashboard 左侧导航。
- 暗色侧栏下的“系统”分组与子菜单。
- 用户在 Web UI 中切换系统诊断、日志、用量、运行记录等页面时的可读性。

当前事实：

- Web E2E 登录后展开左侧底部“系统”分组。
- 子菜单区域呈浅色块，但仍处于暗色侧栏上下文，文字对比度过低，几乎不可读。
- 其它页面主体导航和接口调用无 console error/warn，问题集中在侧栏菜单视觉状态。

建议修复方向：

- 先定位侧栏菜单的暗色主题覆盖规则。
- 优先使用现有 Ant Design Vue menu 主题或最小 CSS 覆盖修复子菜单背景与文字对比度。
- 修复后用 Web build 和浏览器复核，避免影响主内容区菜单样式。

修复记录：

- `AppSidebar.vue` 为系统子菜单增加 `nav-system-items` 容器。
- `AppSidebar.scss` 对暗色主题下系统子菜单的默认、hover、active 状态增加显式背景与文字颜色，避免浅色块继承暗色侧栏上下文导致文字不可读。
- 验证：`node --experimental-strip-types web/tests/systemNavItemsMetadataStatic.test.ts`、相关侧栏静态测试、`npm --prefix web run build` 均通过。

## BUG-033：聊天失败后会话列表统计未体现失败消息和失败运行

状态：已修复（2026-07-05）

影响范围：

- Dashboard 会话列表。
- 真实模型失败后的聊天记录统计。
- 用户排查失败 run 时的状态可见性。

当前事实：

- Web E2E 发送聊天后，真实模型请求返回 `401 Invalid API Key`，run 失败且 `attempts=2`。
- UI 已展示用户消息、两次尝试和 401 错误。
- `/api/sessions?limit=5` 返回新会话 `message_count=0`，token 统计也为 0。

建议修复方向：

- 先确认产品语义：失败 run 是否应计入会话消息数。
- 若 UI 已展示失败消息，则会话列表至少应能反映该会话存在失败交互，避免列表统计与聊天面板矛盾。
- 修复应优先在会话统计的共享查询或映射层完成，不在多个前端调用处补丁。

修复记录：

- `DashboardSessionService` 接入 `AgentRunRepository`，当会话正文为空但存在失败运行时，用运行输入预览和错误状态补足会话列表的 `message_count` 与 `preview`。
- `DashboardConfiguration` 与 `DefaultToolRegistry` 传入同一运行仓储，保持 Dashboard API 与会话管理工具的摘要语义一致。
- 新增 HTTP 回归测试覆盖“空 NDJSON 会话 + 失败 run + `/api/sessions` 摘要”场景；验证先红后绿。

## BUG-034：Windows 上缺少真实 Node TUI PTY E2E 执行能力

状态：待处理，当前审计脚本已能稳定报告平台限制

影响范围：

- Windows 开发机上的真实 Node TUI PTY E2E。
- 无人值守流程中对 TUI 真实键盘输入、终端绘制和退出路径的端到端复核。
- 后续需要覆盖真实交互式终端时的跨平台质量门禁。

当前事实：

- 侧车 TUI E2E 复测中，命令级审计 `/help`、`/status`、`/doctor`、`/sessions` 全部通过，`audit.findings=0`。
- 真正启动 Node TUI PTY 的路径在 Windows 上返回 `pty_not_supported_on_this_platform`。
- 该结果不是 TUI 指令语义失败，而是当前审计路径依赖类 Unix PTY 能力，Windows 侧缺少 ConPTY 实现。

根因：

- `scripts/audit-terminal-commands.py` 的真实 PTY 路径使用 `fcntl`、`pty`、`select`、`termios` 等类 Unix 终端模块。
- Windows 环境不能直接提供这些模块语义，导致真实 Node TUI PTY E2E 只能稳定退出为“不支持平台”。

建议修复方向：

- 若继续要求 Windows 本机真实 PTY E2E，应补充 ConPTY 后端或改用已有可维护的 Windows PTY 封装。
- 若短期只要求质量门禁稳定，应在文档和 CI 中明确：Windows 走命令级审计，真实 PTY 绘制复核走类 Unix 环境。
- 修复前不要把 `pty_not_supported_on_this_platform` 误判为 TUI 命令失败。

验证命令：

```bash
python scripts/audit-terminal-commands.py --no-defaults --timeout-seconds 12 --command /help --command /status --command /doctor --command /sessions
```

## BUG-035：Dashboard 默认登录提示指向不存在的启动日志令牌

状态：已修复（2026-07-05）

影响范围：

- Dashboard 首次登录页。
- 使用默认 `java -jar` 启动且未显式配置 Dashboard 访问令牌的本地用户。
- Web E2E 登录路径和排障指引。

当前事实：

- Web E2E 默认启动后，登录页提示“令牌在服务端启动日志中查看”。
- 后端默认配置 `solonclaw.dashboard.accessToken` 留空，非公开 API 会按安全边界返回 401。
- 启动日志不会输出 Dashboard 访问令牌，用户按页面提示无法找到可用令牌。

根因：

- 登录页文案沿用旧的“启动日志查看令牌”假设，但后端当前不会生成或打印令牌。
- 空访问令牌拒绝非公开 API 是正确安全边界，问题在错误指引而不是鉴权逻辑。

修复记录：

- 8 个 locale 的 `login.description` 改为指向真实配置键 `solonclaw.dashboard.accessToken`，提示通过服务端配置或启动参数传入。
- 新增 `loginDescriptionStatic.test.ts`，锁定登录说明必须包含 Dashboard 访问令牌配置键，避免再次回退到日志指引。

验证命令：

```bash
node --experimental-strip-types web/tests/loginDescriptionStatic.test.ts
node --experimental-strip-types web/tests/i18nRegistrationStatic.test.ts
node --experimental-strip-types web/tests/loginStoredTokenValidationStatic.test.ts
```

## 当前结论

- BUG-025 至 BUG-029 已有提交和 focused 验证，属于本轮新增闭环记录。
- BUG-030 保留为待复核项，不在缺少稳定复现前改代码。
- BUG-031、BUG-032 与 BUG-033 已修复，并补充 focused 验证记录。
- BUG-034 记录 Windows 真实 Node TUI PTY E2E 的剩余平台限制；当前命令级审计仍可作为 Windows 可用性门禁。
- BUG-035 已修复默认登录页错误指引，保持空令牌拒绝访问的安全边界不变。
- 仓库内仍缺正式 Web/TUI 浏览器级 E2E 入口；当前无人值守复测继续通过真实 Chrome/真实 TTY 侧车代理补证据。
