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

## 已验证

- `npm --prefix web run test:device-login-modal-reuse`
- `npm --prefix web run test:platform-qr-panel-reuse`
- `npm --prefix web run build`
- `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.useIncrementalCompilation=false -Dtest=DashboardResponseTest,DashboardControllerHttpTest#shouldReturnStructuredErrorForInvalidProviderValidationRequest test`
- `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.useIncrementalCompilation=false -Dtest=DashboardResponseTest,DashboardControllerHttpTest#shouldUpdateDashboardPlatformToolsetPolicy+shouldPersistConfigAndExposeDashboardResources test`
- `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.useIncrementalCompilation=false -Dtest=DashboardResponseTest,DashboardMediaServiceTest,DashboardCuratorServiceTest,DashboardSessionServiceTest#shouldRedactSecretLikeSessionIdentifiersFromDashboardResponses,AgentMechanismTest#shouldActivateAgentForUnpersistedDashboardSession,McpPackageSecurityServiceTest test`
- `git diff --check`
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`

说明：命名门禁运行前需要临时移走未跟踪 `.omo/` 团队产物；该目录内有历史审计文本命中命名规则，但不属于本阶段产品提交范围。

## 延后或不合并项

- 后端 QR setup 状态机：只读复核确认微信与飞书/钉钉协议差异较大，强行抽共享 `TicketState` 会破坏私有状态边界并影响反射测试；本阶段不做。
- `DashboardCronController`：错误响应状态码按 `isNotFound(e)` 动态分流，留到后续更细测试后再收敛。
- `DashboardWorkspaceController`：错误文本经过 `workspaceErrorMessage(e)` 特殊转换，不能按普通控制台错误响应机械替换。
- `DashboardTuiRuntimeController`：JSON-RPC 错误语义不同，不纳入普通 Dashboard response 合并。

## 阶段结论

阶段 3.2 已完成高收益且可安全验证的融合合并项。剩余重复主要属于可配置复用、测试 fixture 复用、公开工具重载参数对象化或前端通用组件抽取，应进入阶段 3.3 逐项处理。
