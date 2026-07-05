# 阶段 3.1 功能重复检测增量清单

日期：2026-06-30

复核时间：2026-07-04

复核时间：2026-07-05

## 结论

当前 HEAD 已包含 2026-06-29 到 2026-06-30 的阶段 3.2、3.3、3.4 修复。原阶段 3.1 清单中的设备码登录弹窗、平台二维码面板、控制台错误响应、测试安全策略桩、Markdown 文档编辑面板、TUI 审批测试构造、渠道设置字段行和 QR 错误状态等问题已被不同提交缓解。

本轮按当前源码重新做增量复核，原 7 类候选中多数已收口或缓解，当前只保留仍有状态源收益的语义重复候选。

2026-07-04 复核后，完全重复扫描已无输出，平台设置字段、命令服务构造器链和定时任务工具重载转发等低风险重复项已由后续提交继续收口。当前不再把这些项作为待修复候选；剩余候选只保留需要语义边界设计的后端 QR 生命周期与审批确认状态源。

2026-07-05 复核后，`min-lines=40` 和 `min-lines=25` 完全重复扫描均无输出。Web 设置页和 Web TUI Runtime 页已共用 `useChannelQrPolling`，前端 QR 候选收窄为 terminal-ui 与 Web 共享状态契约的对齐问题；后端旧候选仍为 QR setup ticket 生命周期、审批 / slash confirm 状态源边界。新补齐的 Runs 可恢复运行入口和 Jobs guide / policy 面板没有引入新的重复功能面。

2026-07-05 命令时间格式化复用提交后再次复核，完全重复扫描仍无输出。阶段 3.1 当前只保留语义重复候选：QR setup 后端 ticket 生命周期，以及审批 / slash confirm 的诊断命名和状态源边界；其余旧候选已收口或不再构成高置信功能重复。

## 检查方法

- 只读并行扫描：
  - Web Dashboard：页面、组件、API 包装、共享展示 helper。
  - Java 后端 / TUI / 工具系统：二维码 setup、审批、TUI RPC、Dashboard 控制器和自然语言工具入口。
- 本地重复检测：
  - `python3 scripts/check-code-duplication.selftest.py`：通过。
  - `python3 scripts/check-code-duplication.py --report-only --min-lines 40 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`：无输出，说明当前没有 40 行以上完全重复块。
  - 2026-06-30：`python3 scripts/check-code-duplication.py --report-only --min-lines 25 --max-findings 80 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`：仍有 23 组 25 行以上完全重复块。
  - 2026-07-05：`python scripts/check-code-duplication.py --report-only --min-lines 25 --max-findings 80 src/main/java src/test/java web/src terminal-ui/src terminal-ui/packages`：无输出。
  - 2026-07-05 命令时间格式化复用提交后：`python scripts/check-code-duplication.selftest.py`、`--min-lines 40`、`--min-lines 25 --max-findings 80` 均通过且无重复输出。

## 当前高置信候选

### 1. 平台设置字段仍适合继续配置驱动化

状态：已收口。

- 位置：
  - `web/src/components/solonclaw/settings/PlatformSettings.vue`
  - `web/src/components/solonclaw/settings/PlatformOptionalSettings.vue`
  - `web/src/components/solonclaw/settings/PlatformSwitchSettingRow.vue`
  - `web/src/components/solonclaw/settings/PlatformTextSettingRow.vue`
- 重叠程度：高。
- 证据：阶段 3.3 已把单行 `Input` / `Switch` 壳抽出，但两个设置页仍手写平台分支和字段拼装；差异主要是平台 key、字段名、提示文案和保存函数参数。
- 建议：阶段 3.3 继续抽平台字段配置表，先从 `wecom`、`qqbot`、`yuanbao` 三个平台的可选设置做低风险改造，再评估飞书、钉钉、微信主设置。
- 2026-07-04 复核：已由 `578287ae9` 和 `b488dca0d` 分别收口可选平台与主平台字段配置驱动化；当前只保留平台差异入口和保存动作。

### 2. 平台 QR 展示和 Web 轮询已统一，terminal-ui 状态契约仍待对齐

- 位置：
  - `web/src/components/solonclaw/settings/ChannelQrPanel.vue`
  - `web/src/shared/channelQrPolling.ts`
  - `web/src/shared/channelQr.ts`
  - `web/src/components/solonclaw/settings/PlatformSettings.vue`
  - `web/src/views/solonclaw/TuiRuntimeView.vue`
  - `terminal-ui/src/components/channelQr.ts`
  - `terminal-ui/src/components/channelSetup.tsx`
- 重叠程度：中。
- 证据：共享面板已覆盖扫码、等待、失败、过期和外链状态；`useChannelQrPolling` 已覆盖 Web 设置页和 Web TUI Runtime 页的 state、polling 和 URL 选择。
- 2026-07-05 二次复核：剩余重复主要在 terminal-ui 自己维护 active status、URL / message 提取和轮询定时器，后端 QR RPC 已由 `TuiRuntimeProtocolService` 统一包装。
- 建议：阶段 3.3 先补 Web / terminal-ui QR 状态契约测试；只有测试暴露分歧时，再抽 terminal-ui 的状态归一 helper，Ink 展示层继续保留。

### 3. 模型提供方字段元数据重复

状态：已收口。

- 位置：
  - `web/src/components/solonclaw/models/ProviderCard.vue`
  - `web/src/components/solonclaw/models/ProviderFormModal.vue`
- 重叠程度：中。
- 证据：展示卡片和编辑弹窗都围绕 `providerKey`、`name`、`baseUrl`、`defaultModel`、`dialect`、`apiKey` 做字段展示、dialect 语义和基础校验。
- 建议：抽共享字段元数据、dialect 选项和基础校验规则；保留卡片展示和表单编辑两个视图。
- 2026-07-04 复核：已由 `291348278` 和 `e1a98833f` 收口。当前 `providerDisplay.ts` 统一提供卡片字段、表单标签、dialect 目录、占位符和健康状态文案；`providerDisplayOptions.test.ts` 与 `modelProviderCatalogStatic.test.ts` 已锁定前端复用后端 provider/dialect catalog。

### 4. 作业摘要与作业卡片存在同数据重复呈现

状态：已缓解。

- 位置：
  - `web/src/views/solonclaw/JobsView.vue`
  - `web/src/components/solonclaw/jobs/JobsPanel.vue`
  - `web/src/components/solonclaw/jobs/JobCard.vue`
- 重叠程度：中。
- 证据：顶部 `status-panel` 展示总数、活跃、暂停、完成、到期、失败、下次运行；列表卡片又展示同一批作业状态和下次运行信息。
- 建议：阶段 3.4 或 5.3 评估信息架构，压缩顶部摘要，把明细留给列表卡片，避免重复占屏。
- 2026-07-04 复核：阶段 5 已删除教学式 hero，并把下次运行明细交给即将运行列表；当前不再作为重复呈现待修复项。

### 5. 用量指标口径重复

状态：已收口。

- 位置：
  - `web/src/components/solonclaw/usage/StatCards.vue`
  - `web/src/components/solonclaw/usage/DailyTrend.vue`
- 重叠程度：中。
- 证据：两个组件都组织 `tokens`、`cacheRead`、`cacheWrite`、`cost`、`sessions` 等同一指标集合，只是一个展示汇总，一个展示趋势。
- 建议：抽共享指标定义或列定义，后续新增指标时只改一处。
- 2026-07-04 复核：`usageMetrics.ts` 与 `usageFormat.ts` 已成为统计卡片、日趋势图和表格窗口的共享入口，静态测试已覆盖。

### 6. QR setup 执行状态机仍存在后端重复

- 位置：
  - `src/main/java/com/jimuqu/solon/claw/web/WeixinQrSetupService.java`
  - `src/main/java/com/jimuqu/solon/claw/web/DomesticQrSetupService.java`
  - `src/main/java/com/jimuqu/solon/claw/web/QrSetupTicketState.java`
- 重叠程度：高。
- 证据：两个服务都有 `start/get/shutdown`、ticket 状态表、异步 executor、状态标记、失败标记、过期与 map 输出；差异是微信 iLink 与飞书/钉钉协议。
- 2026-07-05 二次复核：`QrSetupTicketState` 已收口通用字段、时间、mark/fail；剩余重复是 tickets map、executor、start/get/shutdown、toMap、sleep 与安全 URL 包装等生命周期壳。
- 建议：低优先级进入阶段 3.2；只抽 `QrSetupTicketStore` 或 `toBaseMap` 这类生命周期壳，协议 polling 继续留在各平台服务。

### 7. 审批 / slash confirm / TUI 响应语义仍有重叠

- 位置：
  - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DangerousCommandApprovalService.java`
  - `src/main/java/com/jimuqu/solon/claw/gateway/command/SlashConfirmService.java`
  - `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java`
  - `src/main/java/com/jimuqu/solon/claw/tool/runtime/ApprovalQueueManageTools.java`
  - `terminal-ui/src/domain/approvalRespond.ts`
  - `terminal-ui/src/app/useInputHandlers.ts`
- 重叠程度：中高。
- 证据：后端审批服务维护审批状态、观察者、卡片动作、TTL 和持久化；slash confirm 又维护 pending confirm、过期清理、always 命令和 resolve；Dashboard 暴露 `/api/diagnostics/approvals*` 与 `/api/diagnostics/slash-confirms*` 两组入口，TUI 侧再拼装 approval response 并处理拒绝/清理。
- 2026-07-05 二次复核：两类功能都呈现为“待处理审批/确认”，但危险操作审批和 slash 命令二次确认的状态源语义不同，不应硬合并。
- 建议：下一轮先统一 UI / 诊断命名边界：危险操作用 approval，命令二次确认用 confirm；`ApprovalQueueManageTools` 继续聚合只读查询，不迁移状态源。

## 仍有完全重复块但暂不作为功能重复的项

2026-07-04 和 2026-07-05 复核时，`min-lines=25` 当前无输出。旧报告中记录的 23 组完全重复块已由后续提交和生成目录忽略规则收口。

已收口项包括：

- `DefaultCommandService` 构造器转发链：`5e774dd49`。
- `CronjobTools` 工具方法重载参数桥接：`5f291f12d`。
- 生成目录重复噪声：`dc87fae03`。

判断：当前无需为了阶段 3 再做机械重复抽取，下一步只处理有明确用户价值或状态源收益的语义重复。

## 已缓解项

- 设备码登录弹窗：已由 `DeviceCodeLoginModal` 承担核心状态机，提供方组件仅保留薄 wrapper。
- 平台二维码面板：已由 `ChannelQrPanel.vue` 承担共享展示；前端轮询状态机重复已重新列入当前候选 2。
- 控制台错误响应：多数 Dashboard 控制器已复用 `DashboardResponse` 错误入口。
- 测试安全策略桩、Markdown 文档面板、TUI 审批测试监听器构造、渠道字段行：已由阶段 3.3 原子项处理。
- 平台设置字段配置化、模型提供方字段元数据化、用量指标元数据化、Jobs 摘要去重、命令构造器链和 CronjobTools 重载桥接：已由后续提交处理。
- TUI setup/channel RPC：已明显向 `TuiRuntimeProtocolService` 收敛，当前主要剩薄入口和状态语义统一问题。

## 特别复核项

- Runs 可恢复运行入口：重叠程度低。Web、API、工具入口都围绕同一 `AgentRunRepository` / `DashboardRunService`，TUI `/resume` 是会话恢复，不是 run-level recoverable；不进入阶段 3.2 / 3.3 / 3.4。
- Jobs guide / policy 面板：重叠程度低。新面板是折叠的能力与策略摘要，复用 `CronJobService.guide/policy`；`JobCard` / `JobsPanel` 展示具体作业实例状态，功能面不同；不进入阶段 3.2 / 3.3 / 3.4。

## 推荐后续顺序

1. 阶段 3.2：只剩 QR setup 后端 ticket 生命周期抽取，或审批 / slash confirm 状态源边界收敛；这两项属于语义重复，进入前需要单独测试计划。
2. 阶段 3.3：补 Web / terminal-ui QR 状态契约测试；仅在测试暴露分歧时抽 terminal-ui 状态归一 helper。
3. 阶段 3.4 / 5.3：Jobs 摘要重复已缓解，后续只在真实 E2E 发现扫读问题时再继续优化。
