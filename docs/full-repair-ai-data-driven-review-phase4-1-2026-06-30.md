# 阶段 4.1 AI 与数据驱动硬编码增量审查

日期：2026-06-30

## 对应能力点

- 对应外部对标仓库中的 Agent 决策、工具调用、自然语言操作、Dashboard 配置、用量统计和主动协作能力。
- 本文是 2026-06-27 阶段 4 文档之后的增量审查，不重复记录已经落地的 `HeuristicGoalJudge`、主动协作证据链和 Dashboard 窄工具补齐项。
- 本轮排除已经完成的渠道设置字段行复用、可选设置字段行复用和二维码无图错误态增强。

## 总体结论

1. 当前最适合先改的是低风险展示规则：用量模型排行的比例计算、趋势表展示窗口、统计卡片指标配置。这些问题不需要新增模型调用，也不会碰安全边界。
2. Provider、平台定义、slash 帮助和审批策略摘要适合做成后端元数据或规则数据驱动，但需要先整理接口契约，不能直接把前端静态表删掉。
3. 主动协作软决策可以继续强化真实反馈闭环；静默时间、日限额、冷却、危险命令、工具循环防护等硬门控仍必须保持确定性规则优先。
4. AiAgent 全局操作能力的底层工具骨架已经存在，剩余缺口主要是自然语言到 Dashboard 页面级动作的统一语义，而不是缺少万能 HTTP 调用工具。

## 2026-07-05 当前复核

以下候选在当前源码中已完成，不再作为后续直接待办：

- 用量模型排行比例已由 `ModelBreakdown.vue` 通过 `maxUsageValue(...)` 计算真实最大值，并由 `modelBreakdownUsageScaleStatic.test.ts` 锁定。
- 用量统计卡片、日趋势列和表格窗口已由 `usageMetrics.ts` 与 `usageFormat.ts` 统一提供指标元数据和格式化入口。
- Provider 与模型目录已通过后端 `/api/providers`、`dialectCatalog`、前端 `providerDisplay.ts` 和模型 store 收敛，前端静态表只保留展示兜底。
- 平台定义已由 `platformCatalog` 与平台字段配置驱动，飞书、钉钉、微信和可选渠道设置页不再各自复制字段壳。
- Slash command 帮助已由 `CommandRegistry` 驱动；当前只保留 usage override，因为命令描述符尚无统一 usage 字段，直接扩字段收益低于风险。
- 审批策略摘要已从危险规则目录、硬阻断规则、配置项、审批观察者和审计策略生成样例和计数；执行策略仍保持确定性规则优先。
- 审批策略里的终端护栏摘要键和计数已由 `DangerousCommandRuleCatalog.terminalGuardrailKeys()` 统一派生，覆盖真实检测分支中的 detached session，避免列表和计数漂移。
- `hardline_metadata_url` 动态硬阻断规则键、硬阻断计数、覆盖工具和阻断类别已收敛到 `DangerousCommandRuleCatalog`，服务检测和摘要展示不再各自手写。

当前阶段 4.1 后续只保留三类高价值方向：主动协作软决策的真实反馈闭环、Dashboard 页面级自然语言操作语义盘点、以及安全规则命中频次/审计解释的数据化。硬阻断、安全放行、URL 策略和工具循环阻断不交给模型决定。

## 增量候选清单

| 优先级 | 位置 | 硬编码表现 | 建议改造方向 | 风险 |
| --- | --- | --- | --- | --- |
| P0 | `web/src/components/solonclaw/usage/ModelBreakdown.vue:8-38` | 条形宽度用 `usageStore.modelUsage[0].totalTokens` 做分母，隐含第一项一定最大。 | 显式计算 `maxTokens`，空数组和乱序数据都按最大值归一化；补单元或组件测试覆盖乱序模型用量。 | 低 |
| P1 | `web/src/components/solonclaw/usage/DailyTrend.vue:19-21,55-72` | 最近 30 条、日期截断、tooltip 和表格列顺序写死。 | 后端用量接口返回展示窗口、聚合粒度和指标元数据；前端按列定义渲染，先保留当前规则作为默认值。 | 中 |
| P1 | `web/src/components/solonclaw/usage/StatCards.vue:8-55` | 卡片数量、币种缺省、空值文案和格式化规则固定在组件内。 | 抽出共享用量格式化工具，后续接入后端价格和币种元数据；卡片改成指标配置数组渲染。 | 低 |
| P1 | `web/src/shared/providers.ts:13-253` | provider 名称、base URL、模型列表由前端静态维护。 | 由后端 provider registry/model catalog 下发；前端只保留当前确认协议范围内的兜底项和展示逻辑。 | 中 |
| P1 | `web/src/components/solonclaw/settings/platformDefinitions.ts:7-38` | 平台 key、展示名和 SVG 图标在前端静态数组中。 | 后端返回平台 code、displayName、enabled、iconKey；前端只映射少量本地图标资源。 | 中低 |
| P1 | `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveDecisionService.java:126-149,166-199` | 软决策 fallback 文案和跳过原因固定；硬门控原因标签固定。 | 保留硬门控；只让软决策文案、触达优先级和候选排序结合历史触达、用户响应和真实会话内容。 | 中 |
| P1 | `src/main/java/com/jimuqu/solon/claw/tool/runtime/DangerousCommandApprovalPolicySummaries.java:65-160,169-209` | 审批策略摘要的样例、布尔能力项和英文描述固定。 | 从规则目录、命中频次和审批日志生成摘要样例；执行策略仍由规则和配置决定。 | 低 |
| P1 | `src/main/java/com/jimuqu/solon/claw/gateway/command/SlashCommandHelpRenderer.java:70-150` | slash 帮助命令、参数和说明逐行手写。 | 改成命令注册表驱动的帮助生成，真实命令目录作为单一来源。 | 低 |
| P2 | `src/main/java/com/jimuqu/solon/claw/agent/AgentRuntimeService.java:74-79,201-205` | 默认 Agent 展示名、描述和校验错误文案固定。 | 默认 profile 与文案字典配置化；名称校验规则继续确定性执行。 | 低 |
| P2 | `src/main/java/com/jimuqu/solon/claw/tool/runtime/ToolCallLoopGuardrailService.java:41-97,125-138,174-225` | 工具幂等/变更名单和循环阈值固定。 | 先记录运行统计和误报样本，只输出阈值建议；阻断策略不交给模型。 | 中高 |
| P2 | `src/main/java/com/jimuqu/solon/claw/tool/runtime/DangerousCommandRuleCatalog.java` | 危险命令规则目录大量正则和关键词写死。 | 可外置成规则数据并增加命中观测，不改成模型放行。 | 高 |

## AiAgent 操作能力增量判断

已确认入口：

- `src/main/java/com/jimuqu/solon/claw/tool/runtime/DefaultToolRegistry.java:1250-1535` 已注册记忆、会话、用量、日志、媒体、技能、MCP、provider、状态、诊断、工作区、浏览器、运行、Agent、定时任务等窄工具。
- `terminal-ui/src/app/createSlashHandler.ts:54-166` 已有 TUI slash 本地命令、后端 `slash.exec` 和 `command.dispatch` 回退链路。
- `web/src/router/index.ts:14-101` 已有 chat、agents、jobs、models、logs、usage、runs、skills、settings、diagnostics、tui-runtime、curator、channels、gateways、mcp、files 等页面入口。

剩余缺口：

- `src/main/java/com/jimuqu/solon/claw/web/DashboardTuiRuntimeController.java:64-99` 的 JSON-RPC 仍主要覆盖 setup、model、channel、config，不覆盖 agents、jobs、runs、mcp、diagnostics、files 等页面级操作。
- “页面存在”和“自然语言能稳定完成页面操作”不是一回事。后续应优先盘点 `slash.exec` 和 `command.dispatch` 的后端命令目录，再决定是否补最小 RPC 或命令语义。
- 不建议新增万能 Dashboard HTTP 工具；高风险写入、OAuth、下载、回滚、删除和聊天运行主链仍应保留更强确认边界。

## 建议执行顺序

1. 主动协作软决策继续补真实反馈闭环，优先让候选排序和触达建议结合历史触达、用户响应和真实会话内容；静默时间、日限额、冷却和危险命令硬门控保持确定性。
2. Dashboard 页面级自然语言操作先做命令/RPC 目录盘点，再补最小语义入口；不要新增万能 Dashboard HTTP 工具。
3. 安全规则、工具循环防护、协议枚举和 hard gate 不作为 AI 优先改造入口，只做命中频次、解释、配置建议和审计增强。
4. Agent 默认 profile 与用户可见文案可继续配置化，但不应阻塞更高价值的真实反馈和自然语言操作链路。

## 验证建议

- 前端用量组件改造：`npm --prefix web run build`
- 组件级测试优先新增或复用：`npm --prefix web run test:<usage-component>`
- 后端命令/工具改造：`mvn -Dskip.web.build=true -DskipTests compile`
- 提交前命名门禁：`python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`

## 剩余风险

- 本轮是增量审查，不代表所有硬编码已经穷尽。
- Provider 与模型清单具有时效性，直接写死在前端会持续漂移；但一次性改为动态 catalog 会牵涉配置、默认值和模型选择 UI，需要单独原子化。
- 安全相关硬编码数量很多，但其中大量是必须稳定的安全契约；阶段 4 不应为了“AI 驱动优先”削弱审批和阻断确定性。
