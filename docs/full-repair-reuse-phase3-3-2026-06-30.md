# 阶段 3.3 功能复用改造进度记录

日期：2026-06-30

## 对应能力点

- Dashboard-first setup：控制台与配置页面继续收敛重复交互壳，页面只保留业务加载、保存和路由逻辑。
- 测试支撑复用：安全策略边界测试使用同一组测试桩，避免多个测试类复制本地回环和固定 DNS 行为。
- 记忆 / 人格文件：Markdown 文档查看与编辑壳统一复用，保留各页面的数据来源和文案差异。

## 已完成复用项

### 1. 测试安全策略桩复用

- 提交：`bbbc4de2d refactor: 复用测试安全策略桩 / Reuse test security policy fixtures`
- 新增 `SecurityPolicyTestSupport`，统一提供固定 DNS 解析测试桩和允许本地回环但继续阻断云元数据地址的测试桩。
- 替换 `AppUpdateServiceTest`、`DefaultSkillHubHttpClientTest`、`DomesticQrSetupServiceTest`、`WeixinQrSetupServiceTest`、`RuntimeRefreshBehaviorTest`、`BoundedAttachmentIOTest` 中重复的内部类。
- 保留 `ProviderPublicUrlApprovalSecurityPolicyService` 与 token 阻断测试桩，因为它们覆盖的是不同测试语义。

验证：

- `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.useIncrementalCompilation=false -Dtest=SecurityPolicyTestSupportTest test`
- `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.useIncrementalCompilation=false -Dtest=SecurityPolicyTestSupportTest,AppUpdateServiceTest,DefaultSkillHubHttpClientTest,DomesticQrSetupServiceTest,WeixinQrSetupServiceTest,RuntimeRefreshBehaviorTest,BoundedAttachmentIOTest test`
- `git diff --check`
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`

### 2. Markdown 文档编辑面板复用

- 提交：`7e5c727ca refactor: 复用 Markdown 文档编辑面板 / Reuse Markdown document editor panel`
- 新增 `MarkdownDocumentPanel.vue`，统一 Markdown 展示、空态、加载态、textarea 编辑和保存/取消按钮。
- `MemoryView.vue` 和 `PersonaFileView.vue` 改为复用该面板。
- 页面层继续保留 API 调用、路由监听、只读判断、更新时间格式化、文案拼装和 `§` 到 Markdown 段落的展示转换。
- 新增 `test:markdown-document-panel-reuse` 静态测试，锁定两个页面只通过共享面板渲染文档编辑壳。

验证：

- `npm --prefix web run test:markdown-document-panel-reuse`
- `npm --prefix web run test:device-login-modal-reuse`
- `npm --prefix web run test:platform-qr-panel-reuse`
- `npm --prefix web run build`
- `cd web && bun <omo-programming-skill>/scripts/typescript/check-no-excuse-rules.ts tests/markdownDocumentPanelReuseStatic.test.ts`
- Playwright CLI 预览检查：`#/solonclaw/persona/memory`、`#/solonclaw/persona/memory_today`、`#/solonclaw/persona/user` 桌面和移动宽度可渲染，编辑态 textarea 与保存/取消按钮可见。
- `git diff --check`
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`

说明：预览检查时没有后端代理，页面控制台出现 `/api/*` 与 `/health` 的 502 或连接拒绝，属于预览环境限制，不影响本次组件复用的静态渲染、编辑态和构建验证。

### 3. TUI 审批测试监听器构造复用

- 提交：`c11a8dbac refactor: 复用 TUI 审批测试监听器构造 / Reuse TUI approval test listener setup`
- `TerminalUiApprovalRespondTest` 新增 `newTuiListener(...)` 与 `newTuiListenerWithSecurityPolicy(...)`，统一维护 `CliRuntime` 与 `TerminalUiWebSocketListener` 的长参数测试装配。
- 保留各测试里的会话绑定、待审批数据、JSON-RPC 请求和断言叙事，不把审批行为差异抽成测试 DSL，避免隐藏测试意图。
- 重复扫描中 `TerminalUiApprovalRespondTest` 相关的两组 25 行以上重复块已消除，整体重复组数从 23 组降为 21 组。

验证：

- `mvn -Dskip.web.build=true -DskipTests=false -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.useIncrementalCompilation=false -Dtest=TerminalUiApprovalRespondTest test`
- `python3 scripts/check-code-duplication.py --report-only --min-lines 25 --max-findings 80 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`
- `git diff --check`
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`

### 4. 可选渠道设置字段行复用

- 提交：`1e347df2b refactor: 复用可选渠道设置字段行 / Reuse optional channel setting rows`
- 新增 `PlatformTextSettingRow.vue`，统一 `SettingRow + Input` 的文本字段壳，并把 `change` 事件规整为字符串值。
- 新增 `PlatformSwitchSettingRow.vue`，统一 `SettingRow + Switch` 的开关字段壳，并把宽类型开关值规整为布尔值。
- `PlatformOptionalSettings.vue` 中 `wecom`、`qqbot`、`yuanbao` 的 3 个开关字段和 11 个文本字段改为复用共享字段行，保留原平台分支、字段名、保存入口和文案。
- 新增 `test:platform-optional-setting-rows-reuse` 静态测试，锁定可选渠道设置页不再保留重复的 `SettingRow/Input/Switch` 字段壳。

验证：

- `npm --prefix web run test:platform-optional-setting-rows-reuse`
- `npm --prefix web run test:platform-qr-panel-reuse`
- `npm --prefix web run build`
- `bun <omo-programming-skill>/scripts/typescript/check-no-excuse-rules.ts web/src/components/solonclaw/settings/PlatformOptionalSettings.vue web/src/components/solonclaw/settings/PlatformTextSettingRow.vue web/src/components/solonclaw/settings/PlatformSwitchSettingRow.vue web/tests/platformOptionalSettingRowsReuseStatic.test.ts`
- `python3 scripts/check-code-duplication.py --report-only --min-lines 25 --max-findings 80 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`
- `git diff --check`
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`
- Vite preview 视觉检查：`#/solonclaw/channels` 桌面与移动宽度下，企业微信、QQBot、腾讯元宝可选字段展开可见，QQBot App ID 输入可编辑；预览环境无后端，保存请求失败属于预期环境限制。

### 5. 主渠道设置字段行复用

- 提交：`1daaabea1 refactor: 复用主渠道设置字段行 / Reuse primary channel setting rows`
- `PlatformSettings.vue` 中飞书、钉钉、微信的 5 个开关字段和 9 个文本字段改为复用 `PlatformSwitchSettingRow.vue` 与 `PlatformTextSettingRow.vue`。
- 保留三类主渠道原有平台分支、QR 登录面板、保存入口、字段名和文案，不把平台特有配置强行抽进配置表。
- 新增 `test:platform-primary-setting-rows-reuse` 静态测试，锁定主渠道设置页不再保留重复的 `SettingRow/Input/Switch` 字段壳。

验证：

- `npm --prefix web run test:platform-primary-setting-rows-reuse`
- `npm --prefix web run test:platform-optional-setting-rows-reuse`
- `npm --prefix web run test:platform-qr-panel-reuse`
- `npm --prefix web run build`
- `bun <omo-programming-skill>/scripts/typescript/check-no-excuse-rules.ts web/src/components/solonclaw/settings/PlatformSettings.vue web/tests/platformPrimarySettingRowsReuseStatic.test.ts`
- `python3 scripts/check-code-duplication.py --report-only --min-lines 25 --max-findings 80 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`
- `git diff --check`
- `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`
- Vite preview 视觉检查：`#/solonclaw/channels` 桌面与移动宽度下，飞书、钉钉、微信字段和扫码登录入口展开可见；预览环境无后端，`/api/*` 与 `/health` 连接失败属于预期环境限制。

### 6. Web QR setup 轮询状态机复用

- 2026-07-05 本轮提交：`refactor: 复用 Web QR 轮询状态机 / Reuse Web QR polling state`。
- 新增 `useChannelQrPolling`，统一二维码 payload 归一化、二维码图片生成、ticket 轮询、三次失败上限、状态推进和卸载清理。
- `PlatformSettings.vue` 与 `TuiRuntimeView.vue` 改为复用同一个轮询 helper，页面只保留各自 API 入口、确认后刷新动作和提示文案。
- 新增 `test:channel-qr-polling-reuse` 静态测试，锁定 Web 设置页和 Web TUI Runtime 页不再各自维护 QR 图片生成与轮询失败状态。

验证：

- `npm --prefix web run test:channel-qr-polling-reuse`
- `npm --prefix web run test:platform-qr-panel-reuse`
- `npm --prefix web run test:tui-runtime-ui`
- `node --experimental-strip-types web/tests/channelQr.test.ts`
- `npm --prefix web run build`

### 7. TUI QR setup 状态契约补齐

- 2026-07-05 本轮提交：`test: 补齐 TUI QR 状态契约 / Cover TUI QR status contract`。
- `channelQrStatus(...)` 将 `initializing/pending/scanned/failed` 等后端状态归一到 Web Dashboard 使用的 `wait/scaned/expired/error` 契约。
- `channelQrUrl(...)` 补齐 `qrcode_img_content` 与 `qr_code` 字段，避免 TUI 收到后端 data image 或通用 QR code 字段时显示空白。
- `ChannelSetup` 与 `ChannelQrSetupView` 改为通过同一组 helper 判断轮询是否继续、展示哪个状态文本，避免轮询和展示各解释一套状态词。
- 扩展 `channelQr.test.ts`，先红后绿锁定 Web / terminal-ui QR 状态契约。

验证：

- `npm --prefix terminal-ui test -- src/__tests__/channelQr.test.ts`
- `npm --prefix terminal-ui run type-check`
- `npm --prefix terminal-ui run build`
- `npm --prefix terminal-ui run lint`
- `node --experimental-strip-types web/tests/channelQr.test.ts`

## 延后候选

- `ChannelQrPanel.vue` 状态展示边界增强：适合作为阶段 3.4 低风险小项，收紧加载、等待、扫码、确认、失效、错误等状态的文案与按钮可见规则。
- `CronjobTools` 内部请求对象化：可能影响工具签名边界，进入前需先做更细的调用图和兼容性检查。
- `DefaultCommandService` 构造器依赖收敛：属于更大结构调整，不应和当前阶段 3.3 小原子项混合提交。

## 阶段状态

阶段 3.3 已完成七个低风险复用原子项，并补齐 Web / terminal-ui QR 状态契约的测试闭环。下一步可进入阶段 3.4 对 `ChannelQrPanel.vue` 等已融合功能做边界增强评估，或继续按 E2E 路径补真实 Web / TUI 验证脚本。
