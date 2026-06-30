# 阶段 3.1 功能重复检测增量清单

日期：2026-06-30

## 结论

当前 HEAD 已包含 2026-06-29 到 2026-06-30 的阶段 3.2、3.3、3.4 修复。原阶段 3.1 清单中的设备码登录弹窗、平台二维码面板、控制台错误响应、测试安全策略桩、Markdown 文档编辑面板、TUI 审批测试构造、渠道设置字段行和 QR 错误状态等问题已被不同提交缓解。

本轮按当前源码重新做增量复核，仍存在 7 类值得进入后续 3.2 / 3.3 的功能重复或相似功能面。

## 检查方法

- 只读并行扫描：
  - Web Dashboard：页面、组件、API 包装、共享展示 helper。
  - Java 后端 / TUI / 工具系统：二维码 setup、审批、TUI RPC、Dashboard 控制器和自然语言工具入口。
- 本地重复检测：
  - `python3 scripts/check-code-duplication.selftest.py`：通过。
  - `python3 scripts/check-code-duplication.py --report-only --min-lines 40 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`：无输出，说明当前没有 40 行以上完全重复块。
  - `python3 scripts/check-code-duplication.py --report-only --min-lines 25 --max-findings 80 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`：仍有 23 组 25 行以上完全重复块。

## 当前高置信候选

### 1. 平台设置字段仍适合继续配置驱动化

- 位置：
  - `web/src/components/solonclaw/settings/PlatformSettings.vue`
  - `web/src/components/solonclaw/settings/PlatformOptionalSettings.vue`
  - `web/src/components/solonclaw/settings/PlatformSwitchSettingRow.vue`
  - `web/src/components/solonclaw/settings/PlatformTextSettingRow.vue`
- 重叠程度：高。
- 证据：阶段 3.3 已把单行 `Input` / `Switch` 壳抽出，但两个设置页仍手写平台分支和字段拼装；差异主要是平台 key、字段名、提示文案和保存函数参数。
- 建议：阶段 3.3 继续抽平台字段配置表，先从 `wecom`、`qqbot`、`yuanbao` 三个平台的可选设置做低风险改造，再评估飞书、钉钉、微信主设置。

### 2. 平台 QR 面板已统一，但调用分支仍重复

- 位置：
  - `web/src/components/solonclaw/settings/ChannelQrPanel.vue`
  - `web/src/components/solonclaw/settings/PlatformSettings.vue`
- 重叠程度：中。
- 证据：共享面板已覆盖扫码、等待、失败、过期和外链状态；`PlatformSettings.vue` 中仍有飞书、钉钉、微信三段同型调用，只是平台参数和凭据来源不同。
- 建议：不要拆回平台内联实现；后续把 QR 面板调用参数下沉到平台配置表，减少模板分支。

### 3. 模型提供方字段元数据重复

- 位置：
  - `web/src/components/solonclaw/models/ProviderCard.vue`
  - `web/src/components/solonclaw/models/ProviderFormModal.vue`
- 重叠程度：中。
- 证据：展示卡片和编辑弹窗都围绕 `providerKey`、`name`、`baseUrl`、`defaultModel`、`dialect`、`apiKey` 做字段展示、dialect 语义和基础校验。
- 建议：抽共享字段元数据、dialect 选项和基础校验规则；保留卡片展示和表单编辑两个视图。

### 4. 作业摘要与作业卡片存在同数据重复呈现

- 位置：
  - `web/src/views/solonclaw/JobsView.vue`
  - `web/src/components/solonclaw/jobs/JobsPanel.vue`
  - `web/src/components/solonclaw/jobs/JobCard.vue`
- 重叠程度：中。
- 证据：顶部 `status-panel` 展示总数、活跃、暂停、完成、到期、失败、下次运行；列表卡片又展示同一批作业状态和下次运行信息。
- 建议：阶段 3.4 或 5.3 评估信息架构，压缩顶部摘要，把明细留给列表卡片，避免重复占屏。

### 5. 用量指标口径重复

- 位置：
  - `web/src/components/solonclaw/usage/StatCards.vue`
  - `web/src/components/solonclaw/usage/DailyTrend.vue`
- 重叠程度：中。
- 证据：两个组件都组织 `tokens`、`cacheRead`、`cacheWrite`、`cost`、`sessions` 等同一指标集合，只是一个展示汇总，一个展示趋势。
- 建议：抽共享指标定义或列定义，后续新增指标时只改一处。

### 6. QR setup 执行状态机仍存在后端重复

- 位置：
  - `src/main/java/com/jimuqu/solon/claw/web/WeixinQrSetupService.java`
  - `src/main/java/com/jimuqu/solon/claw/web/DomesticQrSetupService.java`
- 重叠程度：高。
- 证据：两个服务都有 `start/get/shutdown`、ticket 状态表、异步 executor、状态标记、失败标记、过期与 map 输出；差异是微信 iLink 与飞书/钉钉协议。
- 建议：不要把协议强行合并；可先抽共享 ticket 生命周期、状态输出和过期处理，平台服务只保留协议动作。

### 7. 审批 / slash confirm / TUI 响应语义仍有重叠

- 位置：
  - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DangerousCommandApprovalService.java`
  - `src/main/java/com/jimuqu/solon/claw/gateway/command/SlashConfirmService.java`
  - `terminal-ui/src/domain/approvalRespond.ts`
  - `terminal-ui/src/app/useInputHandlers.ts`
- 重叠程度：高。
- 证据：后端审批服务维护审批状态、观察者、卡片动作、TTL 和持久化；slash confirm 又维护 pending confirm、过期清理、always 命令和 resolve；TUI 侧再拼装 approval response 并处理拒绝/清理。
- 建议：下一轮先画清“权限状态源”和“UI 确认壳”的边界，优先让 `DangerousCommandApprovalService` 成为唯一状态源，`SlashConfirmService` 和 TUI 只做交互适配。

## 仍有完全重复块但暂不作为功能重复的项

`min-lines=25` 仍报告 23 组完全重复块，其中主要是：

- `DefaultCommandService` 构造器转发链。
- `CronjobTools` 工具方法重载参数桥接。
- 多个测试 fixture 或断言叙事片段。
- `PersonaDiaryView.vue` 与 `SkillsView.vue` 的双栏阅读布局。
- `terminal-ui/packages/solonclaw-ink/dist/**` 构建产物与源码相似。

判断：

- 构造器和重载桥接更适合阶段 3.3 或后续结构性重构，不能和当前功能融合混提交。
- 测试重复需要按测试语义逐个抽 fixture，避免隐藏测试叙事。
- 双栏布局属于可复用 UI 布局，不是业务功能重复。
- `dist` 构建产物不作为源码融合目标。

## 已缓解项

- 设备码登录弹窗：已由 `DeviceCodeLoginModal` 承担核心状态机，提供方组件仅保留薄 wrapper。
- 平台二维码面板：已由 `ChannelQrPanel.vue` 承担共享展示和状态处理。
- 控制台错误响应：多数 Dashboard 控制器已复用 `DashboardResponse` 错误入口。
- 测试安全策略桩、Markdown 文档面板、TUI 审批测试监听器构造、渠道字段行：已由阶段 3.3 原子项处理。
- TUI setup/channel RPC：已明显向 `TuiRuntimeProtocolService` 收敛，当前主要剩薄入口和状态语义统一问题。

## 推荐后续顺序

1. 阶段 3.2：优先做 QR setup 后端 ticket 生命周期抽取，或审批 / slash confirm 状态源边界收敛；这两项属于功能语义重复，收益最高但风险也最高。
2. 阶段 3.3：优先做平台字段配置驱动化、模型提供方字段元数据、用量指标定义复用；这些改动更局部。
3. 阶段 3.4 / 5.3：再处理 Jobs 摘要与列表的信息架构重复，因为它涉及用户如何扫读页面，而不只是代码复用。
