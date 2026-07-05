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

## BUG-036：TUI 后端运行失败事件未在主界面持久展示

状态：已修复（2026-07-05）

影响范围：

- TUI WebSocket 真实聊天失败路径。
- 用户排查模型连接、鉴权或 TLS 失败时的终端反馈。
- 无人值守 TUI E2E 对失败状态的可见性判断。

当前事实：

- TUI E2E 真实聊天失败后，数据库中的 `agent_runs.status=failed`，且事件表有 `run.failed` 与 `attempt.error`。
- 后端非 JSON-RPC WebSocket 路径会向 TUI 发送 `run.failed`，payload 中包含 `error`。
- 终端主界面回到 ready 状态，但没有稳定展示失败文本，用户只能从日志或数据库确认失败原因。

根因：

- `GatewayEvent` 类型和 `createGatewayEventHandler` 只处理通用 `error` 事件。
- 后端已有的 `run.failed` 事件没有进入 TUI 错误展示分支，导致事件被忽略。

修复记录：

- `GatewayEvent` 增加 `run.failed` 事件类型，payload 同时支持 `error` 与 `message`。
- `createGatewayEventHandler` 复用现有 `error` 分支处理 `run.failed`，把错误写入活动状态和持久系统消息。
- 新增 `createGatewayEventHandler.test.ts` 回归用例，先红后绿覆盖后端运行失败事件。

验证命令：

```bash
npm --prefix terminal-ui test -- src/__tests__/createGatewayEventHandler.test.ts
```

## BUG-037：消息网关页面在非默认端口启动时固定显示 8080

状态：已修复（2026-07-05）

影响范围：

- Dashboard 消息网关页面。
- 使用 `--server.port` 或空闲端口启动的本地实例。
- 用户排查渠道连接方式和当前实例地址时的可见信息。

当前事实：

- Web E2E 在 `53227` 端口启动 jar 并打开 Dashboard。
- `/health` 在 `53227` 可访问，`8080` 不可访问。
- 消息网关页面仍显示 `websocket:8080`、`stream:8080` 等固定端口。

根因：

- `web/src/api/solonclaw/gateways.ts` 的 `fetchGateways()` 在前端映射层写死 `port: 8080`。

修复记录：

- 网关状态映射改为读取当前 Dashboard 页面端口，未显式端口时按协议回退到 80/443。
- `gatewayReadOnlyStatic.test.ts` 增加静态回归，禁止网关 API 继续硬编码默认后端端口。

验证命令：

```bash
node --experimental-strip-types web/tests/gatewayReadOnlyStatic.test.ts
```

## BUG-038：TUI 本地 OpenAI 兼容模型地址被阻断时缺少提前提示

状态：已修复（2026-07-05）

影响范围：

- TUI 首次启动 setup 状态。
- TUI 模型选择器。
- 使用 OpenAI 兼容协议连接本机或内网模型服务的用户。

当前事实：

- TUI E2E 中配置 OpenAI 兼容 provider 指向 `http://127.0.0.1:<port>` 后，聊天阶段才失败。
- 后端模型网关按默认安全策略阻断远程 provider 的私有地址访问，错误为 `LLM apiUrl 被安全策略阻断：阻断内网/私有地址`。
- 该安全边界是预期行为；问题是 TUI setup/model 页面没有提前告诉用户需要显式启用 `security.allowPrivateUrls`。

根因：

- `TuiRuntimeProtocolService` 只在模型真正请求时依赖 `SolonAiLlmGateway` 做 URL 安全检查。
- `setup.status` 和 `model.options` 没有复用同一 URL 安全策略做只读预检，导致配置页看起来正常但真实对话失败。

修复记录：

- `setup.status` 对当前 provider base URL 做安全策略预检，若被私有地址策略阻断则返回 `warning`。
- `model.options` 的 provider 条目复用同一预检结果，在模型选择器中展示阻断原因和 `security.allowPrivateUrls=true` 操作提示。
- TUI 设置面板新增 warning 行展示，保留启动路径已有的通用 RPC warning 提示。
- 未放宽 `SolonAiLlmGateway` 的 URL 安全策略；真实模型请求仍保持 fail-closed。

验证命令：

```bash
mvn "-Dskip.web.build=true" "-Dtest=TuiRuntimeProtocolServiceTest,SolonAiLlmGatewayConfigTest" test
npm --prefix terminal-ui test -- src/__tests__/setupPanel.test.ts
npm --prefix terminal-ui run type-check
```

## BUG-039：模型设置页加载失败后误显示空模型状态

状态：已修复（2026-07-05）

影响范围：

- Dashboard 设置页的模型配置面板。
- 模型提供方接口临时失败或权限/网络异常时的用户反馈。

当前事实：

- `modelsStore.fetchProviders()` 失败后会清空 providers，并把错误写入 `modelsStore.loadError`。
- 模型管理页的 `ProvidersPanel` 已展示 `modelsStore.loadError`。
- 设置页的 `ModelSettings` 只判断 `providers.length === 0`，因此接口失败时会显示“暂无已配置的模型”，掩盖真实错误。

根因：

- 设置页模型面板没有消费已有的 `modelsStore.loadError` 状态源，错误态和空状态的展示顺序不一致。

修复记录：

- `ModelSettings` 在空状态之前展示 `modelsStore.loadError` 的错误标题与详情。
- `modelsLoadFailure.test.ts` 增加 SSR 模板回归，锁定设置页加载失败时不再显示空模型提示。

验证命令：

```bash
npm --prefix web run test:models-load-failure
npm --prefix web run build
```

## BUG-040：任务中心指南渲染未知 token 时刷出 i18n 缺失警告

状态：已修复（2026-07-05）

影响范围：

- Dashboard 任务中心页面。
- 后端定时任务 guide / policy 返回新增 token、复合字段名或未翻译运行策略时的浏览器控制台。
- Web E2E 与人工排查前端问题时的信噪比。

当前事实：

- `jobsDisplay.ts` 会把后端 token 依次拼接为 `jobs.humanize.*`、`jobs.fieldHumanize.*`、`jobs.actionHumanize.*` 后调用 `t()` 探测翻译。
- Vue i18n 对缺失 key 会输出 missing-key warning，再返回原 key。
- 未知复合 token 例如 `runtime_isolation` 会触发多次探测，控制台出现大量 `jobs.*` 缺失警告，但页面最终展示回退文本是可用的。

根因：

- 共享任务展示 formatter 用普通 `t(key)` 调用承担“翻译存在性探测”，把预期的未知 token 回退路径误变成 i18n 警告路径。

修复记录：

- `translatedLabel()` 改为使用 Vue i18n 支持的单次调用选项 `missingWarn: false`，只静默当前探测，不修改全局 i18n 配置。
- `jobsDisplay.test.ts` 增加未知复合 token 回归：先确认旧实现会记录缺失警告，再验证修复后仍回退为 `runtime / isolation` 且不产生警告。

验证命令：

```bash
node --experimental-strip-types web/tests/jobsDisplay.test.ts
npm --prefix web run test:i18n-registration
npm --prefix web run test:job-form-options
npm --prefix web run build
```

## BUG-041：TUI 忙碌状态下 `/model` 不打开模型选择器

状态：已修复（2026-07-05）

影响范围：

- 本地 TUI 交互层。
- 模型未配置、对话运行中或 busy 状态下需要打开模型选择器的用户。
- 真实 TTY E2E 中通过 `/model` 进入模型配置/选择流程的路径。

当前事实：

- `createSlashHandler` 直接执行 `/model pick` 可打开 `modelPicker`。
- 真实交互报告显示 busy 状态下输入 `/model` 只进入历史，没有打开模型选择器。
- `/model` 的空参数和 `pick` 分支只是本地 overlay 操作，不会切换模型或修改会话状态。

根因：

- `terminal-ui/src/app/slash/commands/session.ts` 在解析 `/model` 参数之前先调用 `guardBusySessionSwitch('change models')`。
- busy 状态下该 guard 会提前返回，导致本地模型选择器 overlay 没有机会打开。

修复记录：

- 将 busy guard 下移到真正执行 `config.set model` 前。
- 保留输入 `/model <value>` 时的 busy 防护；空参数、`pick` 和只读 `--refresh` 不再被“切换模型”防护误拦。
- `createSlashHandler.test.ts` 增加 busy 状态下 `/model` 打开 `modelPicker` 的回归用例。

验证命令：

```bash
npm --prefix terminal-ui test -- src/__tests__/createSlashHandler.test.ts
npm --prefix terminal-ui run type-check
npm --prefix terminal-ui run build
npm --prefix terminal-ui run lint -- --quiet
```

## BUG-042：TUI `/undo` 和 `/retry` 遇到空响应时没有用户反馈

状态：已修复（2026-07-05）

影响范围：

- 本地 TUI slash 命令 `/undo`。
- 本地 TUI slash 命令 `/retry`。
- 后端会话撤销接口异常返回空响应、旧版本返回空响应或网关适配返回空对象边界时的用户反馈。

当前事实：

- `/undo` 和 `/retry` 都通过 `session.undo` RPC 判断后端是否撤销了消息。
- `createSlashHandler` 的 `guarded` 包装器会在响应为 `null` 时直接跳过回调。
- 因此后端返回空响应时，命令既不报错，也不显示 `nothing to undo` / `nothing to retry`，用户只能看到输入进入历史。

根因：

- `/undo` 和 `/retry` 把“空响应也需要展示 nothing 提示”的业务逻辑包在通用 `guarded` 中。
- 通用 `guarded` 的设计是忽略空响应，适合多数只处理有效载荷的命令，但不适合这两个需要明确空结果反馈的命令。

修复记录：

- `/undo` 和 `/retry` 改为在命令内部先检查 stale，再用 `r?.removed ?? 0` 处理空响应。
- 保留 stale 请求忽略语义，不修改通用 `guarded` 行为。
- `createSlashHandler.test.ts` 增加空响应回归，覆盖 `/undo` 输出 `nothing to undo`，`/retry` 输出 `nothing to retry` 且不发送旧消息。

验证命令：

```bash
npm --prefix terminal-ui test -- src/__tests__/createSlashHandler.test.ts
npm --prefix terminal-ui run type-check
npm --prefix terminal-ui run build
npm --prefix terminal-ui run lint -- --quiet
```

## BUG-043：Web SSE 非 JSON `data:` 帧会中断后续运行事件

状态：已修复（2026-07-05）

影响范围：

- Dashboard 聊天运行事件流。
- 后端、代理或网关偶发输出非 JSON SSE `data:` 帧时的前端恢复能力。
- 用户正在等待后续合法增量、工具状态或完成事件的聊天界面。

当前事实：

- `streamRunEvents()` 将单个 SSE 事件中的 `data:` 行合并后直接执行 `JSON.parse(dataText)`。
- 非 JSON `data:` 帧会抛出 `SyntaxError`，异常进入外层 `catch` 后触发 `onError`。
- 后续已经到达缓冲区或即将到达的合法 SSE 事件不再被处理。

根因：

- 单帧业务 payload 解析错误和网络/读取级错误共用同一个外层异常路径。
- 预期可跳过的坏帧被提升成整个事件流失败。

修复记录：

- `streamRunEvents()` 对单帧 `data:` JSON 解析增加局部 `try/catch`，解析失败时跳过当前帧并继续处理后续 SSE 事件。
- `chatStreamEvents.test.ts` 增加回归：先输入 `data: not-json`，再输入合法 `{"delta":"ok"}`，验证 `onError` 不触发且合法事件继续送达。

验证命令：

```bash
node --experimental-strip-types web/tests/chatStreamEvents.test.ts
npm --prefix web run build
```

## BUG-044：Web settings store 暴露已裁剪的海外渠道配置

状态：已修复（2026-07-05）

影响范围：

- Dashboard 设置/渠道配置状态源。
- 前端配置保存路径。
- 本项目仅保留国内渠道的范围约束。

当前事实：

- `settings.ts` 仍定义 `telegram`、`discord`、`slack`、`whatsapp`、`matrix` 五个 ref。
- `fetchSettings()` 会读取后端当前不会返回的同名字段。
- `saveSection()` 仍接受这些 section 并尝试合并到本地状态。
- 当前页面实际渲染已经走 `platforms` 与国内渠道 catalog，这些 ref 没有真实 UI 消费。

根因：

- 旧渠道状态在平台 catalog 改造后没有同步删除，形成不可见但可被调用的假配置入口。

修复记录：

- 删除 settings store 中五个已裁剪渠道的 ref、fetch 赋值、saveSection 分支和返回暴露。
- `settingsUnsupportedSectionsStatic.test.ts` 增加海外渠道配置不可暴露的静态回归。

验证命令：

```bash
node --experimental-strip-types web/tests/settingsUnsupportedSectionsStatic.test.ts
npm --prefix web run test:platform-catalog-metadata
npm --prefix web run build
```

## BUG-045：TUI 文本协议 `run.completed` 事件被静默丢弃

状态：已修复（2026-07-05）

影响范围：

- 本地 TUI WebSocket 文本协议兼容路径。
- 后端使用非 JSON-RPC envelope 推送完成事件时的最终回复展示。
- 忙碌状态回到 ready 的终端交互路径。

当前事实：

- `TerminalUiWebSocketEventSink` 在 JSON-RPC envelope 模式发送 `message.complete`，但文本协议兼容路径发送 `run.completed`，payload 字段为 `final_reply`。
- TUI `GatewayEvent` 类型只声明了 `message.complete`、`run.failed` 和 `error`，没有声明 `run.completed`。
- `createGatewayEventHandler` 没有默认错误提示分支，未知事件会直接 fall through，因此最终回复不会进入 transcript。

根因：

- 后端完成事件存在 JSON-RPC 与文本协议两套事件名，但前端只实现了 JSON-RPC 的 `message.complete` 完成分支。
- 文本协议完成 payload 使用 `final_reply` 字段，未在前端规范成 `recordMessageComplete()` 需要的 `text`。

修复记录：

- `GatewayEvent` 增加 `run.completed` 事件类型，保留文本协议的 `final_reply` 字段。
- `createGatewayEventHandler` 让 `run.completed` 复用 `message.complete` 完成处理，并把 `final_reply` 映射为 `text`。
- `terminal-ui/README.md` 的事件表补充 `run.completed`，避免后续兼容路径再次被遗漏。
- `createGatewayEventHandler.test.ts` 增加回归用例，覆盖 busy 状态下收到 `run.completed` 后写入助手回复并回到 ready。

验证命令：

```bash
npm --prefix terminal-ui test -- src/__tests__/createGatewayEventHandler.test.ts
npm --prefix terminal-ui run type-check
npm --prefix terminal-ui run build
```

## BUG-046：技能页面加载失败后只写控制台、界面仍显示选择提示

状态：已修复（2026-07-05）

影响范围：

- Dashboard 技能页面。
- 技能接口临时失败或后端不可达时的用户反馈。
- 用户判断技能目录是空、未选择，还是加载失败的排障路径。

当前事实：

- `SkillsView` 的 `loadSkills()` 捕获异常后只调用 `console.error('Failed to load skills:', err)`。
- 页面继续渲染主布局和“请选择技能”提示，用户看不到技能加载失败。
- `skills.loadFailed` 翻译键已存在，问题集中在视图没有持久错误状态。

根因：

- 技能列表加载失败没有写入页面状态，只写浏览器控制台。
- 空选择提示与加载失败态没有区分，失败时会误导为正常未选择技能。

修复记录：

- `SkillsView` 增加 `loadError` 状态，成功加载后清空，失败后保存错误文本。
- 技能页面在列表区域显示 `skills.loadFailed` 和具体错误，避免只依赖 console。
- 新增 `skillsLoadFailureStatic.test.ts`，锁定技能页必须保留并渲染加载失败状态。

验证命令：

```bash
npm --prefix web run test:skills-load-failure
npm --prefix web run test:i18n-registration
npm --prefix web run build
```

## BUG-047：Web 聊天启动前错误气泡刷新后丢失

状态：已修复（2026-07-05）

影响范围：

- Dashboard 聊天发送消息后的启动 run 阶段。
- 附件上传失败、创建 run 失败或后端返回无 run id 的错误展示。
- 用户刷新页面后排查“消息是否发送成功”的路径。

当前事实：

- 用户消息发送后会立即调用 `persistActiveMessages()` 和 `persistSessionsList()`。
- `startRun()` 返回无 `run_id` 时会追加系统错误气泡，但追加后直接 `return`。
- 外层 `catch` 捕获上传或创建 run 失败时也只追加系统错误气泡。
- 这两个错误分支都没有像 `run.failed` 事件分支一样持久化当前消息列表。

根因：

- 聊天 store 只把流式事件阶段的失败视为需要持久化的失败态。
- 启动 SSE 之前发生的错误同样已经在 UI 中可见，但没有写回本地会话消息缓存。

修复记录：

- `sendMessage()` 在无 run id 分支追加错误气泡后持久化当前活动消息。
- `sendMessage()` 外层异常分支追加错误气泡后同样持久化当前活动消息。
- 新增 `chatPreStreamErrorPersistenceStatic.test.ts`，锁定启动前错误分支必须持久化可见错误消息。

验证命令：

```bash
npm --prefix web run test:chat-prestream-error-persistence
npm --prefix web run test:chat-message-merge
npm --prefix web run test:session-selection
npm --prefix web run build
```

## BUG-048：TUI 会话创建/恢复前 setup.status 失败会卡住状态

状态：已修复（2026-07-05）

影响范围：

- TUI 首次启动自动创建会话。
- TUI 恢复指定会话、最近会话或崩溃恢复会话。
- 后端临时断开或 `setup.status` RPC 失败时的状态栏反馈。

当前事实：

- `startNewSession()` 在调用 `session.create` 前先 `await rpc('setup.status')`，但没有捕获该 RPC 失败。
- `resumeById()` 会先把状态设为 `resuming…`，然后调用 `rpc('setup.status').then(...)`，同样没有处理该 promise reject。
- 同文件的 `activateLiveSession()` 已经在失败时输出 `error: ...` 并恢复 `ready`，行为不一致。

根因：

- 会话生命周期把 `session.create` / `session.resume` 的失败恢复视为错误路径，但漏掉了前置 setup 预检失败。
- 前置预检失败发生在状态已经切到 `forging` 或 `resuming` 之后，未恢复就会让用户看到卡住状态。

修复记录：

- `startNewSession()` 用 `try/catch` 覆盖 setup 预检、关闭旧会话和创建会话全流程，失败时输出错误并恢复 `ready`。
- `resumeById()` 给 `setup.status` 链路补充 `.catch()`，失败时输出错误并恢复 `ready`。
- `useSessionLifecycle.test.ts` 增加回归，锁定创建和恢复两条路径都必须处理 setup 预检失败。

验证命令：

```bash
npm --prefix terminal-ui test -- src/__tests__/useSessionLifecycle.test.ts
npm --prefix terminal-ui run type-check
npm --prefix terminal-ui run build
npm --prefix terminal-ui run lint -- --quiet
```

## BUG-049：TUI `/copy` 数字参数会复制错误的助手消息

状态：已修复（2026-07-05）

影响范围：

- TUI 本地 `/copy` 命令。
- 用户按序号复制历史助手回复时的剪贴板内容。
- 输入非法或超范围序号时的安全反馈。

当前事实：

- `/copy` 使用 `parseInt(arg, 10)` 判断和计算目标消息。
- `parseInt('1abc', 10)` 会得到 `1`，导致非法输入复制第一条助手消息。
- `Math.min(parseInt(arg, 10), all.length)` 会把 `/copy 999` 夹到最后一条助手消息。

根因：

- 命令参数解析使用宽松的前缀数字解析，并对超范围数字做静默夹取。
- 用户明确输入的序号无效时应反馈用法，而不是复制另一条消息。

修复记录：

- `/copy` 改为只接受完整匹配的正整数。
- 超过助手消息数量的序号返回 `usage: /copy [number]`，不再静默夹取到最后一条。
- `createSlashHandler.test.ts` 增加非法数字和超范围数字回归。

验证命令：

```bash
npm --prefix terminal-ui test -- src/__tests__/createSlashHandler.test.ts
npm --prefix terminal-ui run type-check
npm --prefix terminal-ui run build
npm --prefix terminal-ui run lint -- --quiet
```

## BUG-050：工作区文件页存在路由但侧边栏没有入口

状态：已修复（2026-07-05）

影响范围：

- Dashboard 工作区文件管理页。
- 从常规侧边栏进入工作区文件、模板文件与配置文件查看/编辑能力。
- 用户发现 `/workspace` / `/files` 直达入口背后的实际页面。

当前事实：

- `router` 已注册 `solonclaw.files`，直达 `#/solonclaw/files` 可以打开文件页。
- `dashboardDirectRoutes` 也把 `/workspace` 和 `/files` 指向该页面。
- 侧边栏导航元数据里没有 `solonclaw.files`，用户只能靠直达地址进入。

根因：

- 文件管理页上线后只补了路由和直接访问映射，遗漏了统一侧边栏目录 `PRIMARY_NAV_ITEMS`。
- `AppSidebar.vue` 已经从共享导航元数据渲染主入口，因此根因在元数据缺项，不在组件渲染层。

修复记录：

- 在 `PRIMARY_NAV_ITEMS` 中添加 `solonclaw.files`，复用已有 `sidebar.files` 本地化文案。
- `appSidebarNavMetadataStatic.test.ts` 增加回归，锁定侧边栏目录必须暴露工作区文件入口。

验证命令：

```bash
npm --prefix web run test:app-sidebar-nav-metadata
node --experimental-strip-types web/tests/dashboardDirectRoutes.test.ts
node --experimental-strip-types web/tests/systemNavItemsMetadataStatic.test.ts
npm --prefix web run build
```

## BUG-051：工作区文件列表修改时间列始终为空

状态：已修复（2026-07-05）

影响范围：

- Dashboard 工作区文件管理页。
- 文件列表的“修改时间”显示与排序。
- 用户保存或恢复工作区文件后判断文件更新时间。

当前事实：

- `FileList.vue` 已经展示 `files.modified` 列，并支持按 `modTime` 排序。
- `/api/workspace/files` 和 `/api/workspace/files/{key}` 返回的文件对象没有修改时间字段。
- 前端 `workspaceFileEntries()` 即使收到文件对象，也固定写入 `modTime: ''`，导致列内容只显示占位符。

根因：

- 后端工作区文件 DTO 只暴露内容和路径，没有把受控文件的 `lastModified` 带给前端。
- 前端映射层没有保留修改时间字段，排序列与展示列没有真实数据源。

修复记录：

- `DashboardWorkspaceService.describeFile()` 返回受控工作区文件的 `modTime`，值为文件系统 `lastModified` 毫秒值，缺失文件返回 `null`。
- `workspaceFileEntries()` 和 `statFile()` 统一把数字毫秒值规范化为 ISO 字符串，保持现有字符串排序与 `formatTimestampText()` 展示路径可用。
- `fileNavigationApiStatic.test.ts` 增加回归，锁定后端数字时间会进入文件列表条目。
- `DashboardControllerHttpTest#shouldHideWorkspaceHostPaths` 增加 API 字段断言，保持不泄露宿主绝对路径。

验证命令：

```bash
npm --prefix web run test:file-navigation-api
mvn "-Dskip.web.build=true" "-Dtest=DashboardControllerHttpTest#shouldHideWorkspaceHostPaths" test
npm --prefix web run build
```

## BUG-052：带 token 的 Dashboard 登录链接不会自动进入应用

状态：已修复（2026-07-05）

影响范围：

- Dashboard 首次打开带 `?token=...` 的登录链接。
- 由 `main.ts` 清理 URL token 后注入到 `window.__LOGIN_TOKEN__` 的登录流程。
- 无本地已保存 token 的新浏览器或隐私窗口。

当前事实：

- `LoginView.vue` 已读取 `window.__LOGIN_TOKEN__`，并把它作为输入框默认值。
- `validateExistingToken()` 会优先校验 URL token，但 `onMounted()` 只有 `hasApiKey()` 为真才调用它。
- 验证成功后只跳转页面，没有把通过校验的 `existingKey` 写入本地会话 token。

根因：

- URL token 支持只接入了“读 token”和“清理 URL”路径，遗漏了自动校验 gate。
- 存储路径仍只存在于手动提交登录表单，自动验证成功不会持久化 token，后续 API 请求仍可能缺少 Authorization。

修复记录：

- `onMounted()` 在存在 URL token 或本地 token 时都执行 `validateExistingToken()`。
- `validateExistingToken()` 在响应成功后调用 `setApiKey(existingKey)`，再跳转到登录目标页。
- `loginStoredTokenValidationStatic.test.ts` 增加回归，锁定 URL token 自动验证和成功持久化。

验证命令：

```bash
node --experimental-strip-types web/tests/loginStoredTokenValidationStatic.test.ts
node --experimental-strip-types web/tests/loginUrlTokenCleanupStatic.test.ts
node --experimental-strip-types web/tests/loginUrlTokenNormalization.test.ts
node --experimental-strip-types web/tests/dashboardAuthRedirectStatic.test.ts
npm --prefix web run build
```

## BUG-053：Dashboard 系统页面缺少短路径直达入口

状态：已修复（2026-07-05）

影响范围：

- Dashboard 诊断、TUI 运行时、策展与 MCP 页面。
- 用户从浏览器地址栏直接访问常用系统页面的短路径体验。
- 后端 Dashboard 单页入口的敏感 token 注入保护范围。

当前事实：

- 前端已存在 `solonclaw.diagnostics`、`solonclaw.tui-runtime`、`solonclaw.curator` 和 `solonclaw.mcp` 路由。
- 共享短路径映射只覆盖 `/status`、`/chat`、`/sessions`、`/files` 等入口，没有覆盖上述系统页的同名短路径。
- 后端 `DashboardPageController` 没有声明这些短路径，直接访问会绕不开单页入口映射缺口。

根因：

- 新增系统页面时只补了应用内路由，没有同步补齐浏览器直达短路径表。
- 后端短路径入口以显式 `@Mapping` 方法声明，缺失路径不会自动落到 Dashboard 单页入口。

修复记录：

- `dashboardDirectRoutes` 增加 `/diagnostics`、`/tui-runtime`、`/curator`、`/mcp` 到对应 Hash 路由的映射。
- `DashboardPageController` 增加四个同名短路径入口，统一返回 Dashboard 单页入口。
- `dashboardDirectRoutes.test.ts` 和 `DashboardControllerHttpTest#shouldAvoidInjectingDashboardTokenAndProtectSensitiveApis` 增加回归，锁定短路径可访问且不会注入敏感 token 占位符。

验证命令：

```bash
node --experimental-strip-types web/tests/dashboardDirectRoutes.test.ts
mvn "-Dskip.web.build=true" "-Dtest=DashboardControllerHttpTest#shouldAvoidInjectingDashboardTokenAndProtectSensitiveApis" test
npm --prefix web run build
```

## BUG-054：TUI slash 补全漏掉本地可执行命令和别名

状态：已修复（2026-07-05）

影响范围：

- TUI 输入 `/` 后的 Tab 补全候选。
- `/details`、`/density`、`/logs`、`/save`、`/terminal-setup`、`/replay` 等本地 TUI 命令。
- `/exit`、`/sb`、`/fork`、`/dense` 等本地别名。

当前事实：

- React TUI 的本地 slash 注册表已经能执行这些命令和别名。
- 后端 `complete.slash` RPC 只枚举 Java 命令注册表，并手工补了少量 setup/config 类命令。
- 例如输入 `/de` 时只返回 `/debug`、`/deny`，没有返回可执行的 `/details` 和 `/density`。

根因：

- TUI 补全的后端候选源没有同步包含前端本地命令注册表中只在客户端执行的命令。
- 别名同样没有进入补全候选，用户输入已支持的别名前缀时无法发现可执行命令。

修复记录：

- `TerminalUiRpcService.completeSlash()` 补充 TUI 本地命令和常用别名候选。
- 复用已有 `appendLocalSlashCompletion()` 去重与前缀过滤逻辑，避免新增协议或前端改动。
- `TerminalUiRpcServiceTest#completeSlashIncludesTuiLocalCommandsAndAliases` 增加回归，覆盖本地命令和别名补全。

验证命令：

```bash
mvn "-Dskip.web.build=true" "-Dtest=TerminalUiRpcServiceTest#completeSlashIncludesTuiLocalCommandsAndAliases" test
npm --prefix terminal-ui test -- slashParity.test.ts createSlashHandler.test.ts
```

## 当前结论

- BUG-025 至 BUG-029 已有提交和 focused 验证，属于本轮新增闭环记录。
- BUG-030 保留为待复核项，不在缺少稳定复现前改代码。
- BUG-031、BUG-032 与 BUG-033 已修复，并补充 focused 验证记录。
- BUG-034 记录 Windows 真实 Node TUI PTY E2E 的剩余平台限制；当前命令级审计仍可作为 Windows 可用性门禁。
- BUG-035 已修复默认登录页错误指引，保持空令牌拒绝访问的安全边界不变。
- BUG-036 已修复 TUI `run.failed` 事件被忽略导致失败不可见的问题。
- BUG-037 已修复消息网关页面固定显示 `8080` 的端口误导。
- BUG-038 已修复 TUI 本地 OpenAI 兼容模型地址被安全策略阻断时缺少提前提示的问题，安全策略本身不放宽。
- BUG-039 已修复模型设置页加载失败时误显示空模型状态的问题。
- BUG-040 已修复任务中心未知 token 翻译探测导致浏览器控制台刷 i18n 缺失警告的问题。
- BUG-041 已修复 TUI busy 状态下 `/model` 被切换模型防护误拦、无法打开模型选择器的问题。
- BUG-042 已修复 TUI `/undo` 与 `/retry` 空响应被静默吞掉、用户无反馈的问题。
- BUG-043 已修复 Web SSE 非 JSON `data:` 帧导致整个运行事件流提前失败的问题。
- BUG-044 已修复 Web settings store 暴露已裁剪海外渠道假配置入口的问题。
- BUG-045 已修复 TUI 文本协议 `run.completed` 事件被静默丢弃导致最终回复不显示的问题。
- BUG-046 已修复 Dashboard 技能页面加载失败后只写控制台、界面无失败态的问题。
- BUG-047 已修复 Web 聊天启动前错误气泡刷新后丢失的问题。
- BUG-048 已修复 TUI 会话创建/恢复前 setup.status 失败导致状态卡住的问题。
- BUG-049 已修复 TUI `/copy` 非法或超范围数字参数会复制错误助手消息的问题。
- BUG-050 已修复工作区文件页存在直达路由但侧边栏没有可发现入口的问题。
- BUG-051 已修复工作区文件列表修改时间列没有真实数据源、始终显示占位符的问题。
- BUG-052 已修复带 `token` 的 Dashboard 登录链接在新浏览器中不会自动校验和持久化的问题。
- BUG-053 已修复 Dashboard 诊断、TUI 运行时、策展与 MCP 页面缺少短路径直达入口的问题。
- BUG-054 已修复 TUI slash 补全漏掉本地可执行命令和别名的问题。
- 仓库内仍缺正式 Web/TUI 浏览器级 E2E 入口；当前无人值守复测继续通过真实 Chrome/真实 TTY 侧车代理补证据。
