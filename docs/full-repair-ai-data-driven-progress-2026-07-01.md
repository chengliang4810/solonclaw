# 阶段 4 AI 与数据驱动进度复核

日期：2026-07-01

## 对应外部对标能力点

- 用量统计：Dashboard 应根据真实用量数据、价格可用性和指标元数据展示结果，避免把展示比例、表格窗口和指标顺序散落在多个组件里。
- 对话内命令：slash command 帮助应来自真实命令目录，避免帮助文本和可执行命令漂移。
- 安全审批：审批与硬阻断规则必须保持确定性，阶段 4 只做摘要、观测和展示数据化，不把放行决策交给模型。

## 当前已落地项

### 1. 用量模型排行比例按真实最大值归一化

状态：已完成。

当前证据：

- `web/src/components/solonclaw/usage/ModelBreakdown.vue` 已通过 `computed` 计算 `maxTokens`。
- 条形宽度使用 `m.totalTokens / maxTokens`，不再假设第一条模型用量最大。
- `web/tests/modelBreakdownUsageScaleStatic.test.ts` 已锁定乱序模型用量的静态约束。

### 2. 用量统计卡片使用共享指标元数据

状态：已完成。

当前证据：

- `web/src/shared/usageMetrics.ts` 已提供 `usageStatCardMetrics`。
- `web/src/components/solonclaw/usage/StatCards.vue` 通过 `usageStatCardMetrics.map(...)` 渲染卡片。
- `web/tests/usageStatCardsMetadataStatic.test.ts` 已锁定卡片指标配置入口。

### 3. 日趋势表格和提示列使用共享指标元数据

状态：已完成。

当前证据：

- `web/src/shared/usageMetrics.ts` 已提供 `dailyUsageTrendMetrics`。
- `web/src/shared/usageFormat.ts` 已提供 `latestUsageRows(...)` 和 `DEFAULT_USAGE_TABLE_LIMIT`。
- `web/src/components/solonclaw/usage/DailyTrend.vue` 已用共享列定义生成 tooltip 和表格列。
- `web/tests/dailyTrendColumnsStatic.test.ts` 已锁定列元数据和表格窗口入口。

### 4. slash command 帮助已由命令注册表驱动

状态：已完成当前低风险部分。

当前证据：

- `src/main/java/com/jimuqu/solon/claw/command/CommandRegistry.java` 是命令名称、范围和说明的单一来源。
- `src/main/java/com/jimuqu/solon/claw/gateway/command/SlashCommandHelpRenderer.java` 通过 `CommandRegistry.all()` 渲染 gateway 可用命令。
- `src/test/java/com/jimuqu/solon/claw/gateway/command/SlashCommandHelpRendererTest.java` 已验证帮助文本包含每个 gateway 命令，且不包含非 gateway 命令。

保留项：

- `SlashCommandHelpRenderer` 中的 `USAGE_OVERRIDES` 仍保存参数格式。当前 `CommandDescriptor` 没有 usage 字段；为消除这张表新增字段会触及全部命令注册和测试，收益低于风险，暂不做。

## 当前不作为 AI 驱动改造入口的项

- 危险命令规则、URL 安全策略、hardline 阻断和工具循环硬门控继续保持确定性规则优先。
- 阶段 4 后续只允许把这类规则的命中频次、摘要样例、审计解释和配置建议数据化，不允许把安全放行直接交给模型判断。

## 下一项建议

优先继续做低风险元数据化。2026-07-04 复核时，前两项已有当前源码证据，不再作为直接待办：

1. Provider 与模型目录：已由后端 `/api/providers` 与 `dialectCatalog` 下发，前端通过 `providerDisplay.ts` 和模型 store 复用。
2. 平台定义：已有 `platformCatalog` 共享元数据和对应静态/行为测试覆盖。
3. 审批策略摘要：仍可用规则目录和审批日志生成样例，执行策略不变。
