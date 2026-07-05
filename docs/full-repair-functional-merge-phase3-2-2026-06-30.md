# 阶段 3.2 功能融合合并收口记录

日期：2026-06-30

## 对应能力点

- Dashboard-first setup：统一登录、渠道配置和控制台错误响应边界，减少相同交互在多个入口重复维护。
- 国内渠道接入：平台二维码 setup 前端流程统一到同一面板，避免飞书、钉钉、微信各自维护轮询和二维码展示状态。
- 控制台 API：控制器仍保留各自业务入口，只复用统一错误响应包装，不合并业务服务。

## 已完成融合项

### 1. 设备码登录弹窗融合

- 提交：`211803ec0 refactor: 融合设备码登录弹窗 / Merge device-code login modals`
- 新增通用设备码登录弹窗组件，原有两个提供方弹窗改为薄 wrapper。
- 保留各提供方 API、文案和状态差异，不改登录协议。

### 2. 平台二维码设置面板融合

- 提交：`e98cd4d32 refactor: 融合平台二维码设置面板 / Merge platform QR setup panels`
- 新增统一渠道二维码面板，抽出平台定义和平台专属可选设置块。
- 保留飞书、钉钉、微信各自后端 setup 协议，仅统一前端轮询、二维码展示和刷新交互。

### 3. 控制台错误响应入口复用

- 提交：`860d1367e refactor: 复用控制台错误响应 / Reuse dashboard error responses`
- 在 `DashboardResponse` 中新增带 `Context` 和 HTTP 状态码的错误响应入口。
- `DashboardChatController` 与 `DashboardProviderController` 改为复用该入口，并新增 `DashboardResponseTest` 锁定状态码设置和错误脱敏契约。

### 4. 控制台错误响应调用收敛

- 提交：`ae94d5f39 refactor: 收敛控制台错误响应调用 / Consolidate dashboard error calls`
- `DashboardConfigController`、`DashboardPlatformToolsetsController`、`DashboardRunController` 与 `DashboardSkillsController` 改为复用统一错误响应入口。
- 状态码、错误码、异常类型和成功路径保持不变。

### 5. 更多控制台错误响应复用

- 提交：`b70275177 refactor: 复用更多控制台错误响应 / Reuse more dashboard error responses`
- `DashboardAgentController`、`DashboardCuratorController`、`DashboardMcpController`、`DashboardMediaController` 与 `DashboardSessionController` 改为复用统一错误响应入口。
- 原有 `context != null` 防御由 `DashboardResponse.error(context, ...)` 内部统一保留。

### 6. 后端 QR setup ticket 生命周期复用

- 2026-07-05 本轮提交：`refactor: 复用二维码 ticket 生命周期 / Reuse QR ticket lifecycle`。
- 新增 `QrSetupTicketState`，统一 ticket 标识、初始化状态、过期时间、状态更新时间、失败错误脱敏和接口时间格式化。
- 2026-07-05 本轮补充：`QrSetupTicketState.baseMap()` 统一输出 ticket、状态、错误和时间字段，微信与国内扫码服务只追加各自协议字段。
- `WeixinQrSetupService` 与 `DomesticQrSetupService` 只保留各自协议字段和 HTTP / 持久化流程，避免把微信 iLink、飞书、钉钉协议强行合并。
- 新增 `QrSetupTicketStateTest` 与 `QrSetupTicketMapReuseTest` 锁定 ticket 生命周期、失败脱敏、基础字段投影和服务复用边界，并删除微信测试中对私有 `TicketState` / `fail()` 的反射依赖。

## 已验证

- `npm --prefix web run test:device-login-modal-reuse`
- `npm --prefix web run test:platform-qr-panel-reuse`
- `npm --prefix web run build`
- `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.useIncrementalCompilation=false -Dtest=DashboardResponseTest,DashboardControllerHttpTest#shouldReturnStructuredErrorForInvalidProviderValidationRequest test`
- `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.useIncrementalCompilation=false -Dtest=DashboardResponseTest,DashboardControllerHttpTest#shouldUpdateDashboardPlatformToolsetPolicy+shouldPersistConfigAndExposeDashboardResources test`
- `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.useIncrementalCompilation=false -Dtest=DashboardResponseTest,DashboardMediaServiceTest,DashboardCuratorServiceTest,DashboardSessionServiceTest#shouldRedactSecretLikeSessionIdentifiersFromDashboardResponses,AgentMechanismTest#shouldActivateAgentForUnpersistedDashboardSession,McpPackageSecurityServiceTest test`
- `mvn "-Dskip.web.build=true" "-Dtest=QrSetupTicketStateTest,WeixinQrSetupServiceTest,DomesticQrSetupServiceTest,TuiRuntimeProtocolServiceTest" test`
- `mvn "-Dskip.web.build=true" "-Dtest=WeixinQrSetupServiceTest,DomesticQrSetupServiceTest,QrSetupTicketStateTest,QrSetupTicketMapReuseTest" test`
- `python scripts/check-code-duplication.py --report-only --min-lines 25 --max-findings 80 src/main/java/com/jimuqu/solon/claw/web/WeixinQrSetupService.java src/main/java/com/jimuqu/solon/claw/web/DomesticQrSetupService.java src/main/java/com/jimuqu/solon/claw/web/QrSetupTicketState.java`
- `python scripts/check-code-duplication.py --report-only --min-lines 25 --max-findings 80 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`
- `git diff --check`
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`

说明：命名门禁运行前需要临时移走未跟踪 `.omo/` 团队产物；该目录内有历史审计文本命中命名规则，但不属于本阶段产品提交范围。

## 延后或不合并项

- 后端 QR setup 协议流程：本轮只抽 ticket 生命周期，不合并微信 iLink、飞书、钉钉的协议请求与凭据持久化流程。
- 危险命令审批与 slash confirm 状态源：危险命令审批绑定 AgentSession / snapshot、审批选择器、会话级授权和恢复执行；slash confirm 仅按 `sourceKey` 暂存待确认的 slash 控制命令，确认后一次性消费。Dashboard 与 TUI 只是复用现有 resolve/respond 入口，不存在第三份状态源，因此不做业务状态融合。
- `DashboardCronController`：错误响应状态码按 `isNotFound(e)` 动态分流，留到后续更细测试后再收敛。
- `DashboardWorkspaceController`：错误文本经过 `workspaceErrorMessage(e)` 特殊转换，不能按普通控制台错误响应机械替换。
- `DashboardTuiRuntimeController`：JSON-RPC 错误语义不同，不纳入普通 Dashboard response 合并。

## 阶段结论

阶段 3.2 已完成高收益且可安全验证的融合合并项。剩余重复主要属于可配置复用、测试 fixture 复用、公开工具重载参数对象化或前端通用组件抽取，应进入阶段 3.3 逐项处理。
