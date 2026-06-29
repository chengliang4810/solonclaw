# 阶段 3.1 功能重复检测清单

日期：2026-06-29

## 对应能力点

- Dashboard-first setup：配置、诊断、渠道二维码 setup 和工作区配置入口需要保持统一交互和统一错误边界。
- 国内渠道接入：飞书、钉钉、微信、企业微信、QQBot、元宝的配置和扫码 setup 应避免复制多套状态机。
- 本地 TUI / Web Dashboard：TUI 审批、运行态查看和 Web 诊断面板需要共享稳定协议边界，避免测试和页面各自复制初始化流程。
- 定时任务与命令系统：Slash 命令、工具命令和 Dashboard API 的同类参数桥接需要逐步收敛。

## 审计方法

1. 执行重复检测脚本：
   - `python3 scripts/check-code-duplication.selftest.py`
   - `python3 scripts/check-code-duplication.py --report-only --min-lines 40 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`
   - `python3 scripts/check-code-duplication.py --report-only --min-lines 25 --max-findings 80 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`
2. 并发只读审计：
   - 后端 Java 控制器、服务、渠道适配和测试重复扫描。
   - 前端页面、组件、API 包装和静态测试重复扫描。
3. 人工复核关键候选，排除只是同一设计系统、同一响应壳或协议边界导致的相似结构。

当前结果：

- `min-lines=40`：无完全重复块。
- `min-lines=25`：发现 26 组候选，主要集中在命令服务构造器桥接、CronjobTools 重载、测试 helper、前端登录弹窗和工作区/技能页面布局。

## 高优先级重复功能候选

### 1. 设备码登录弹窗克隆

- 位置：
  - `web/src/components/solonclaw/models/CodexLoginModal.vue:1`
  - `web/src/components/solonclaw/models/NousLoginModal.vue:1`
- 重叠程度：高。
- 证据：
  - 两个弹窗都维护同一组状态：`idle`、`loading`、`waiting`、`approved`、`expired`、`error`。
  - 两者都有启动登录、轮询、复制用户码、打开验证链接、成功后延迟关闭、失败后重试和卸载时停止轮询。
  - 模板和样式几乎同构，只替换接口函数、i18n key 和 CSS 类名前缀；第二个弹窗额外处理 `denied` 状态。
- 判断：
  - 这是同一种设备码授权交互的两套实现，不只是视觉一致性。
- 建议阶段：
  - 阶段 3.2：融合为通用设备码登录弹窗骨架，保留 provider 专属 API、文案和额外状态处理。

### 2. 平台设置表单与二维码登录流程重复

- 位置：
  - `web/src/components/solonclaw/settings/PlatformSettings.vue:19`
  - `web/src/components/solonclaw/settings/PlatformSettings.vue:180`
  - `web/src/api/solonclaw/config.ts:56`
  - `web/src/api/solonclaw/config.ts:137`
  - `web/src/components/solonclaw/settings/PlatformCard.vue:1`
- 重叠程度：高。
- 证据：
  - 多个平台重复“启用开关、凭据输入、保存、loading 状态、成功/失败提示”的表单流程。
  - 飞书、钉钉、微信重复“开始二维码登录、轮询状态、刷新二维码、确认后刷新设置”的前端状态机。
  - `config.ts` 已有凭据 key 映射，但页面仍在模板里逐平台复制输入控件、保存字段和 QR 面板。
- 判断：
  - `PlatformCard.vue` 是合理容器，不是问题；重复点在平台表单字段渲染、保存动作和 QR 状态机。
- 建议阶段：
  - 阶段 3.2：先融合二维码登录面板。
  - 阶段 3.3：再把平台凭据字段渲染改为数据驱动配置，避免一次性改动过大。

### 3. 后端二维码 setup 状态机重复

- 位置：
  - `src/main/java/com/jimuqu/solon/claw/web/WeixinQrSetupService.java:118`
  - `src/main/java/com/jimuqu/solon/claw/web/DomesticQrSetupService.java:126`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardGatewayController.java:35`
- 重叠程度：高。
- 证据：
  - 两个服务都维护 ticket map、异步 executor、状态字段、过期时间、轮询、`mark/fail/toMap`、关闭 executor。
  - 差异主要是平台协议：微信 iLink 与飞书/钉钉注册流程不同。
- 判断：
  - 重复的是“二维码 setup 工作流骨架”，不是具体平台协议。
- 建议阶段：
  - 阶段 3.2 / 3.3：先抽 ticket state、状态输出、过期处理等小组件；不要把不同平台协议硬合并成一个大 service。

### 4. Dashboard 控制器请求解析和错误包装重复

- 位置：
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardConfigController.java:83`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardConfigController.java:138`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardRuntimeConfigController.java:49`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardRuntimeConfigController.java:71`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardRuntimeConfigController.java:104`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsController.java:68`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsController.java:197`
- 重叠程度：中高。
- 证据：
  - 多个控制器重复执行 JSON body 读取、必须为 JSON object 的校验、`IllegalArgumentException` / `IllegalStateException` 到 400 响应的映射。
  - `DashboardConfigController` 已有局部 `safeConfig()`，`DashboardRuntimeConfigController` 仍在每个方法手写同类 try/catch，`DashboardDiagnosticsController` 另有局部 payload helper。
- 判断：
  - 业务 endpoint 不能合并；可合并的是控制层的请求解析和错误响应小工具。
- 建议阶段：
  - 阶段 3.2：抽一个薄的 Dashboard request/response helper，先服务配置和诊断控制器。

## 中优先级复用候选

### 5. CronjobTools 多重重载参数桥接重复

- 位置：
  - `src/main/java/com/jimuqu/solon/claw/tool/runtime/CronjobTools.java:302`
  - `src/main/java/com/jimuqu/solon/claw/tool/runtime/CronjobTools.java:424`
  - `src/main/java/com/jimuqu/solon/claw/tool/runtime/CronjobTools.java:651`
  - `src/main/java/com/jimuqu/solon/claw/tool/runtime/CronjobTools.java:814`
  - `src/main/java/com/jimuqu/solon/claw/tool/runtime/CronjobTools.java:926`
- 重叠程度：中高。
- 证据：
  - 重复检测在该文件内报告多组 25 行以上重复块。
  - 主要重复来自 create/update 构建同一 `body(...)` 参数列表，以及多个 `cronjob(...)` 重载把大量参数补 `null` 后转发到主实现。
- 判断：
  - 这是参数桥接重复，后续新增字段会放大风险。
- 建议阶段：
  - 阶段 3.3：引入内部请求对象或 builder，保留 ToolMapping 对外签名，减少主逻辑重复参数列表。

### 6. DefaultCommandService 构造器链重复

- 位置：
  - `src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java:400`
  - `src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java:479`
  - `src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java:559`
  - `src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java:1216`
  - `src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java:1333`
- 重叠程度：中。
- 证据：
  - 重复检测前三组全在该文件的构造器注释、参数列表和 `this(...)` 转发链。
  - 文件接近 4000 行阈值，继续追加构造器依赖会持续扩大重复。
- 判断：
  - 这是历史增量依赖导致的构造器桥接重复，不是用户功能重复。
- 建议阶段：
  - 阶段 3.3：结合大文件拆分，用依赖包对象或工厂减少构造器重载；不要在 3.2 功能融合阶段直接大改主命令服务。

### 7. 测试安全策略桩重复

- 位置：
  - `src/test/java/com/jimuqu/solon/claw/AppUpdateServiceTest.java:364`
  - `src/test/java/com/jimuqu/solon/claw/DefaultSkillHubHttpClientTest.java:248`
  - `src/test/java/com/jimuqu/solon/claw/DomesticQrSetupServiceTest.java:271`
  - `src/test/java/com/jimuqu/solon/claw/WeixinQrSetupServiceTest.java:295`
  - `src/test/java/com/jimuqu/solon/claw/RuntimeRefreshBehaviorTest.java:1011`
  - `src/test/java/com/jimuqu/solon/claw/BoundedAttachmentIOTest.java:351`
- 重叠程度：高。
- 证据：
  - 多个测试重复定义“允许 localhost、阻断元数据地址”的 `SecurityPolicyService` 子类。
  - 阶段 1.4 已处理危险命令测试 helper，但此类 HTTP/QR/刷新测试的策略桩仍重复。
- 判断：
  - 这是测试支撑重复，抽取风险低，但涉及 URL 安全边界，应单独验证相关测试。
- 建议阶段：
  - 阶段 3.3：抽到测试 support，逐个测试类替换。

### 8. TUI 审批响应测试初始化重复

- 位置：
  - `src/test/java/com/jimuqu/solon/claw/TerminalUiApprovalRespondTest.java:28`
  - `src/test/java/com/jimuqu/solon/claw/TerminalUiApprovalRespondTest.java:77`
  - `src/test/java/com/jimuqu/solon/claw/TerminalUiApprovalRespondTest.java:151`
  - `src/test/java/com/jimuqu/solon/claw/TerminalUiApprovalRespondTest.java:208`
  - `src/test/java/com/jimuqu/solon/claw/TerminalUiApprovalRespondTest.java:282`
  - `src/test/java/com/jimuqu/solon/claw/TerminalUiApprovalRespondTest.java:340`
  - `src/test/java/com/jimuqu/solon/claw/TerminalUiApprovalRespondTest.java:384`
  - `src/test/java/com/jimuqu/solon/claw/TerminalUiApprovalRespondTest.java:441`
- 重叠程度：中高。
- 证据：
  - 重复检测报告多组 `TerminalUiApprovalRespondTest` 内部 25 行以上重复块。
  - 每个测试重复创建 `TestEnvironment`、`CliRuntime`、`TerminalUiWebSocketListener`、`RecordingSocket` 并构造 JSON-RPC 帧。
- 判断：
  - 测试场景不同，但初始化和 socket 发送/断言 helper 可复用。
- 建议阶段：
  - 阶段 3.3：抽测试 fixture，不合并测试叙事。

### 9. 工作区文件编辑页与人格文件页重复

- 位置：
  - `web/src/views/solonclaw/MemoryView.vue:120`
  - `web/src/views/solonclaw/MemoryView.vue:228`
  - `web/src/views/solonclaw/PersonaFileView.vue:107`
  - `web/src/views/solonclaw/PersonaFileView.vue:184`
- 重叠程度：中。
- 证据：
  - 重复检测报告两个页面有 25 行以上重复块。
  - 两者都维护加载、编辑、保存、取消、Markdown 预览和 textarea 编辑的流程。
- 判断：
  - 页面入口和数据源需要保留；重复点是可编辑 Markdown 文档面板。
- 建议阶段：
  - 阶段 3.3：抽通用 Markdown 文件编辑组件，先保持当前视觉和交互不变。

### 10. 技能页与人格日记页的双栏阅读布局重复

- 位置：
  - `web/src/views/solonclaw/PersonaDiaryView.vue:105`
  - `web/src/views/solonclaw/PersonaDiaryView.vue:210`
  - `web/src/views/solonclaw/SkillsView.vue:118`
  - `web/src/views/solonclaw/SkillsView.vue:168`
- 重叠程度：中。
- 证据：
  - 重复检测报告两个页面的 sidebar、main、mobile backdrop、empty detail 样式块重复。
  - 两者都是移动端可收起侧栏 + 主内容区结构。
- 判断：
  - 这是可复用布局，不是功能重复；应作为低风险 UI 布局组件候选。
- 建议阶段：
  - 阶段 3.3：若后续同时改技能和日记页面，再抽 `SplitExplorerLayout` 一类通用布局。

## 已复核但不建议合并

- `DashboardResponse`：已经是共享响应基础设施，不是重复实现。
- `DashboardGatewayController`：只是二维码 setup 路由层，不应和 setup service 合并。
- `DashboardDiagnosticsController` 整体：诊断契约复杂，不能因为 payload helper 相似就与配置控制器合并。
- `ChannelAllowListSupport`、`ChannelConnectionSupport`、`ChannelHttpSupport`：这些是职责单一的小工具，不是重复功能。
- `SettingsView.vue` 与 `DiagnosticsView.vue`：都展示诊断/JSON 数据，但一个是设置附属配置审视，一个是运行态综合诊断台，职责不同。
- `FilesView.vue` 与 persona 系列页面：同为分栏布局，但文件管理器与人格文档浏览/编辑的功能边界不同。
- `AppSidebar.vue` 与 `web/src/router/index.ts`：导航入口和路由表是消费关系，不是重复实现。
- `terminal-ui/packages/solonclaw-ink/dist/entry-exports.js` 与源码重复：`dist` 是包内构建产物，不作为本阶段源码融合目标。

## 推荐进入阶段 3.2 的顺序

1. 设备码登录弹窗融合：收益高、范围集中在两个 Vue 组件和对应调用处。
2. 平台二维码登录面板融合：收益高，但会影响渠道设置页，需要 Web build 和静态测试。
3. Dashboard 控制器请求解析和错误包装 helper：收益中高，涉及 Java 控制器，需补或复用 HTTP 控制器测试。
4. QR setup 服务公共状态输出：先抽 ticket state / `toMap` / `mark` / `fail`，不要合并平台协议。

## 推荐留到阶段 3.3 的顺序

1. CronjobTools 内部请求对象，降低重载参数转发重复。
2. DefaultCommandService 构造器依赖收口，最好和大文件拆分一起做。
3. HTTP/QR/刷新测试的安全策略桩复用。
4. TUI 审批测试 fixture 复用。
5. Markdown 文件编辑组件和双栏阅读布局组件。

## 验证记录

- `python3 scripts/check-code-duplication.selftest.py`：通过。
- `python3 scripts/check-code-duplication.py --report-only --min-lines 40 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`：无输出。
- `python3 scripts/check-code-duplication.py --report-only --min-lines 25 --max-findings 80 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`：发现 26 组候选，已人工筛选。
- 两个只读并发审计代理分别完成后端与前端重复功能复核；本文件已合成其结论并按当前 worktree 复核。

## 剩余风险

- 本阶段是阶段 3.1 清单，不修改生产代码。
- 动态重复、运行时行为重叠和视觉体验重叠仍依赖人工判断；后续 3.2/3.3 每个融合项必须单独提交并单独验证。
