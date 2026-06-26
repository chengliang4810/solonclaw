# 阶段 4.1 AI 与数据驱动审查记录

日期：2026-06-27

## 对应能力点

- 对应本地 Agent 连续执行、主动协作、上下文压缩、会话搜索、安全审批和 Dashboard 配置能力。
- 本阶段先审查“哪些硬编码应该改、哪些硬编码不该改”，避免把确定性契约误改成不稳定的模型判断。

## 审查结论

1. `HeuristicGoalJudge` 是阶段 4 的最高优先级改造点。
   - 位置：`src/main/java/com/jimuqu/solon/claw/goal/HeuristicGoalJudge.java`
   - 当前逻辑只检查最后回复是否包含 `completed`、`已完成`、`无法继续`、`需要用户` 等固定词。
   - 风险：
     - 容易把“完成某一步”误判成整个 standing goal 已完成。
     - 没有结合目标文本、历史轮次、真实任务状态和最后回复的关系。
     - 无法区分“完成了当前子项，下一项继续”和“整个目标已完成”。
   - 建议改造：
     - 引入数据驱动判断上下文，至少包含 goal 文本、turn 使用量、最后回复和当前 goal 状态。
     - 若已有模型调用边界可复用，再增加模型判断；启发式只作为兜底。
     - 保留确定性的空目标、空回复和预算耗尽处理。

2. `MemoryFollowupCollector` 是候选改造点，但不应先动大架构。
   - 位置：`src/main/java/com/jimuqu/solon/claw/proactive/collector/MemoryFollowupCollector.java`
   - 当前逻辑通过多组关键词判断长期记忆是否需要主动跟进。
   - 已有优点：
     - 产出结构化 observation。
     - 保留证据行、来源分区、行号和置信度提示。
     - 过滤普通表达偏好，避免把“回复风格”误当成工作跟进。
   - 风险：
     - 关键词覆盖有限，跨语言、隐含意图和上下文依赖容易漏判。
     - 只看单行，不能利用相邻记忆或近期会话状态。
   - 建议改造：
     - 先确认 proactive 后续是否已有模型 decision 阶段。
     - 若已有模型 decision，采集器可继续保持轻量规则，只增强结构化证据。
     - 若没有模型 decision，再把多行记忆、最近会话摘要和触达次数交给模型判断。

3. `DefaultContextCompressionService` 是中优先级改造点。
   - 位置：`src/main/java/com/jimuqu/solon/claw/engine/DefaultContextCompressionService.java`
   - 当前结构化摘要由规则收集 Goal、Progress、Decisions、Files、Remaining Work。
   - 已有优点：
     - 有 token 阈值、失败冷却、重复压缩抑制和工具参数裁剪。
     - 输出结构稳定，失败影响面小。
   - 风险：
     - `collectKeywords()` 只靠“决定、改为、使用、切换、采用”等词提取决策。
     - 文件提取只按空白切分并查找路径符号，容易混入噪音。
     - 摘要没有真正理解任务状态，仍可能漏掉关键阻塞、验证结果和下一步。
   - 建议改造：
     - 不改 token 预算、冷却和裁剪规则。
     - 在已有规则摘要基础上，补一个可选模型摘要层；失败时保留当前规则摘要。
     - 模型输入必须包含中间窗口、尾部消息、旧摘要和 focus，而不是只统计消息数量。

4. `DefaultSessionSearchService` 当前已经有可选模型摘要，不属于优先硬编码问题。
   - 位置：`src/main/java/com/jimuqu/solon/claw/engine/DefaultSessionSearchService.java`
   - 当前搜索主路径先使用确定性召回、过滤和排序，再按调用方参数决定是否调用模型总结。
   - 判断：
     - 搜索召回和分页限制是确定性数据处理，不应改成模型优先。
     - `SUMMARY_SYSTEM_PROMPT` 属于模型摘要指令，不是问题本身。
   - 后续只需检查 summarize=true 的入口是否覆盖 UI 和工具需要。

5. 安全规则、协议枚举和配置清单不应改成 AI 优先。
   - 代表位置：
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/DangerousCommandRuleCatalog.java`
     - `src/main/java/com/jimuqu/solon/claw/tool/runtime/TirithSecurityService.java`
     - `src/main/java/com/jimuqu/solon/claw/web/McpPackageSecurityService.java`
     - `web/src/shared/providers.ts`
   - 判断：
     - 危险命令、包安全、审批、协议和 provider 清单属于确定性安全或产品契约。
     - 模型可以辅助解释、摘要和审计建议，但不能替代硬规则做放行判断。
   - 后续原则：
     - 保留硬规则为主。
     - 只在诊断说明、误报分析或审计报告中考虑模型辅助。

## 下一步执行顺序

1. 先处理 `HeuristicGoalJudge`，因为它直接影响无人值守 goal 是否错误停止。
2. 改造前先读取 `GoalService`、`GoalJudge`、`GoalVerdict` 和相关测试。
3. 优先做数据驱动上下文增强；只有能复用现有模型调用边界时，才加模型判断。
4. 每次改造必须保留启发式兜底，并补一个能证明误判被修复的最小测试。

## 已明确不改

- 不把安全审批规则交给模型决定。
- 不把协议/provider 清单改成运行时自由生成。
- 不新增大型 AI 决策框架。
- 不为阶段 4.1 引入新依赖。

## 剩余风险

- 本文档是阶段 4.1 的第一轮源码审查，不代表项目内所有硬编码都已穷尽。
- 当前工作树仍存在未纳入本阶段提交的 `terminal-ui/package.json` 与 `terminal-ui/package-lock.json` 本地改动。
