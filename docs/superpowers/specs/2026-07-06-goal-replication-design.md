# `/goal` 全量复刻设计 / Goal Full Replication Design

> **对标能力点：** 外部对标仓库的 `/goal`（跨轮长目标 + judge 驱动自动续轮）与 `/subgoal`（目标补充准则）能力。
> **工作区：** `D:/projects/jimuqu-agent-goal-replication`，分支 `feat/goal-replication-20260706`（基于 `dev`）。

---

## 1. 背景与现状 / Background

外部对标仓库的 `/goal` 是一个跨轮自主循环：用户设一个跨轮长目标，每轮回复后由一个**独立辅助模型（judge）**裁决该目标是否已完成。未完成则把一条**续轮提示作为普通 user 消息**追加到同一会话，驱动 Agent 继续工作，直到目标完成、预算耗尽、或用户暂停/清除。

本仓库已存在一个**最小骨架** `/goal`，但只覆盖了最老的自主循环层：

| 已有 | 缺失（对标） |
|---|---|
| `GoalState` 9 字段（goal/status/turns/max_turns/时间戳…） | `subgoals`、`contract`、`consecutive_parse_failures`、5 个 wait-barrier 字段 |
| `HeuristicGoalJudge`（纯字符串匹配） | **LLM judge**（DONE/CONTINUE/**WAIT**，失败 fail-open） |
| 子命令 `status`/`pause`/`resume`/`clear`/`<text>` | `show`、`draft`、`stop`/`done` 别名、`wait <pid>`、`unwait`；**整个 `/subgoal`** |
| 基础续轮调度 | contract/subgoals 提示变体、**抢占**、Ctrl+C 自动暂停、压缩时父会话归档 |

**关键不变量（对标仓库明确，必须对齐）：**

1. **绝不改写 system prompt、不替换工具集** —— 续轮提示是普通 user 消息，prompt caching 不被破坏。
2. **judge 失败 fail-open（视为 continue）** —— 由轮次预算兜底。
3. **真实用户消息抢占** —— 循环中用户插话，本轮让位给用户。
4. **一次只允许一个活跃目标**（每会话）。

---

## 2. 已确认范围 / 待确认范围

### 已确认范围

- **复刻档位：全量 1:1** —— 覆盖对标仓库 `/goal` 全部 11 个功能面。
- **judge 接入：复用现有 `LlmGateway`，抽一个 auxiliary（非流式、temperature=0）通道**，配置项 `solonclaw.goal.*`。
- **开发节奏：** 落在 `feat/goal-replication-20260706`，按模块分多个 commit，累计 5 次有效 commit 后提示合 `main`。
- **提交规范：** commit message 中英双语 `type: 中文 / English`，命名扫描脚本在提交前运行。

### 待确认范围（本设计先取合理默认，实现期可调）

- **wait-barrier 的 session 触发**：对标仓库用 `process_registry` 的 session 触发/退出/监听模式匹配。本仓库的 `AgentRunSupervisor` / 进程模型与之不同。**默认：** 仅实现 **pid 屏障** 与 **时间屏障**（deadline），session 屏障在本仓库无等价 registry 的前提下**降级为只支持 pid+时间**，并在代码留 TODO 与设计说明，避免引入空壳。
- **Ctrl+C 自动暂停：** 对标仓库在 CLI 进程循环里检测 `_last_turn_interrupted`。本仓库本地 CLI（`CliRuntime`）+ gateway 两条路径。**默认：** gateway 路径无 Ctrl+C 概念，仅在本地 CLI 路径检测中断并 `pause(reason="user-interrupted")`；gateway 路径靠 `/goal pause` 手动暂停。
- **`/goal draft`（辅助模型起草 contract）：** 默认实现，复用同一个 auxiliary 通道。
- **压缩时父会话归档：** 默认实现 `migrate_goal_to_session`（子继承 active，父置 cleared）。
- **kanban goal 模式：** 对标仓库的 `run_kanban_goal_loop` 依赖 kanban 插件，本仓库无 kanban，**明确不做**。

---

## 3. 架构与组件 / Architecture

### 3.1 目标包结构（`com.jimuqu.solon.claw.goal`）

```
goal/
├── GoalState.java          # 扩展：新增 contract/subgoals/parseFailures/5个wait字段
├── GoalContract.java       # 新增：5 字段完成契约 + render_block + 序列化
├── GoalDecision.java       # 扩展：新增 wait/continuation 类型区分（不变签名）
├── GoalVerdict.java        # 扩展：新增 WAIT、WAITING、INACTIVE 枚举值
├── GoalJudge.java          # 扩展：judge(goal, lastResponse, context) -> GoalJudgeResult
├── GoalJudgeRequest.java   # 新增：judge 入参（goal/response/subgoals/contract/background）
├── GoalJudgeResult.java    # 新增：verdict + reason + wait 指令
├── HeuristicGoalJudge.java # 保留：作为 LLM judge 失败/未配置时的回退
├── LlmGoalJudge.java       # 新增：LLM-backed judge（auxiliary 通道）
├── GoalContractParser.java # 新增：inline "field: value" 解析
├── GoalContractDrafter.java# 新增：aux LLM 起草 contract（/goal draft）
├── GoalPromptTemplates.java# 新增：集中所有续轮/judge/draft 提示模板（常量）
├── GoalMigrationSupport.java # 新增：压缩轮转时迁移目标（父归档/子继承）
└── GoalService.java        # 扩展：set/pause/resume/clear/subgoal/wait/unwait/evaluateAfterTurn
```

### 3.2 核心不变量在 Java 侧的落点

| 不变量 | 落点 |
|---|---|
| 不改 system prompt / 工具集 | 续轮提示只走 `runScheduled` 作为 user 消息；`GoalService` 不持有 `ContextService`/`ToolRegistry` |
| judge fail-open | `LlmGoalJudge.judge()` 内部 try/catch，任何异常/超时/解析失败 → 返回 `CONTINUE`；`consecutiveParseFailures` 仅累计"模型有返回但 JSON 不可解析"的情况 |
| 一次一个活跃目标 | `GoalState.status` 状态机 + 压缩迁移归档父会话 |
| 真实消息抢占 | `safeScheduleGoalContinuation` 发续轮前检查"是否有待处理真实用户消息"（见 3.4） |

### 3.3 Judge 调用链（auxiliary 通道）

复用现有 `LlmGateway`，新增一个非流式 auxiliary 调用，**完全镜像** `AsyncSkillLearningService.callAuxiliaryChat`（bounded executor + 4 参 `chat()` + `Future.get(timeout)` + 回退）：

```
GoalService.evaluateAfterTurn(session, lastResponse)
   └─> LlmGoalJudge.judge(GoalJudgeRequest)
         └─> auxiliaryExecutor.submit(() -> llmGateway.chat(
                   judgeSession,                // 复用同一 SessionRecord，但工具集为空
                   JUDGE_SYSTEM_PROMPT,
                   judgeUserPrompt,             // 由模板 + goal/response/subgoals/contract 组装
                   Collections.emptyList()))    // 无工具
         └─> future.get(judgeTimeoutSeconds, SECONDS)   // 默认 30s，可配
         └─> 解析 JSON → GoalJudgeResult
         └─> 异常/超时/空 → GoalJudgeResult.continueGoal("judge unavailable, fail-open")
```

**裁决 JSON 形态（与对标仓库一致）：**
```json
{"verdict": "done", "reason": "..."}
{"verdict": "continue", "reason": "..."}
{"verdict": "wait", "wait_on_pid": 1234, "reason": "..."}
{"verdict": "wait", "wait_for_seconds": 60, "reason": "..."}
```

**judge 使用的模型解析优先级：**
1. `solonclaw.goal.judgeProvider` + `solonclaw.goal.judgeModel`（若配置）
2. 回退到会话默认 provider/model（与 `AsyncSkillLearningService` 一致）

### 3.4 续轮循环与抢占（gateway 路径）

现有循环已工作：`handleGoal` 写 `goal_kickoff` → `safeScheduleGoalKickoff` → `runScheduled` → `applyGoalDecision` 写 `goal_should_continue`+`goal_continuation_prompt` → `safeScheduleGoalContinuation` → 递归。

**新增抢占检查**（最小侵入，不重构整个消息队列）：

在 `safeScheduleGoalContinuation` 发起续轮前，询问 `AgentRunSupervisor`："该 sourceKey 是否有比这条续轮更新的真实用户消息待处理？"
- 若是 → **跳过本轮续轮**，目标保持 active，等待该真实消息处理完后由其 `applyGoalDecision` 重新驱动。
- 若否 → 正常续轮。

实现上：给续轮消息打一个标记 `message.isGoalContinuation() = true`（`GatewayMessage` 新增 transient 标志），`AgentRunSupervisor.coordinateIncoming` 在 busy/排队判定里：真实消息优先于续轮消息。

**续轮提示前缀** `[Continuing toward your standing goal]\nGoal:` 与对标仓库一致，用于识别合成续轮消息，便于 `/goal pause`/`clear` 时清理挂起的续轮。

### 3.5 wait-barrier 惰性自清

`GoalState.isWaiting()`（lazy，对标仓库语义）：
- `waitingOnPid != null` → 查 pid 是否存活（`ProcessHandle.of(pid).isPresent()`，跨平台；对标仓库 `_pid_alive`）
- `waitingUntil > 0` → `now < waitingUntil` 则仍等待
- 满足即视为"仍在等待"，**不消耗轮次、不调 judge**
- 屏障解除后由下一次 `evaluateAfterTurn` 正常裁决

---

## 4. 数据模型 / Data Model

### 4.1 `GoalState` 字段（扩展后，JSON key 用 snake_case 与对标仓库一致）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `goal` | String | "" | 目标文本 |
| `status` | String | "active" | active/paused/done/cleared |
| `turnsUsed` | int | 0 | 已用轮次 |
| `maxTurns` | int | 20 | 预算上限 |
| `createdAt` | long | 0 | 创建时间戳 |
| `lastTurnAt` | long | 0 | 上轮时间戳 |
| `lastVerdict` | String | null | done/continue/wait/skipped |
| `lastReason` | String | null | 上轮裁决原因 |
| `pausedReason` | String | null | 暂停原因 |
| **`consecutiveParseFailures`** | int | 0 | **新增**：judge JSON 连续解析失败数 |
| **`subgoals`** | List\<String\> | [] | **新增**：补充准则 |
| **`waitingOnPid`** | Integer | null | **新增**：等待的进程 pid |
| **`waitingUntil`** | long | 0 | **新增**：等待截止时间戳 |
| **`waitingReason`** | String | null | **新增**：等待原因 |
| **`waitingSince`** | long | 0 | **新增**：等待开始时间戳 |
| **`contract`** | GoalContract | 空 | **新增**：完成契约 |

**向前兼容**：`fromJson` 每个新字段都用 `get(key, default)` 读取，旧 JSON（只有 9 字段）能正常加载（与对标仓库 `from_json` 一致）。

### 4.2 `GoalContract`（5 字段）

| 字段 | label（render_block 显示） | 别名（parse 识别） |
|---|---|---|
| `outcome` | Outcome | outcome, goal, done, "done when" |
| `verification` | Verification | verification, verify, "verified by", evidence, proof |
| `constraints` | Constraints | constraints, constraint, preserve, "must not", "do not change" |
| `boundaries` | Boundaries | boundaries, boundary, scope, allowed, files |
| `stopWhen` | Stop when blocked | "stop when", stop_when, blocked, "stop if blocked", "give up when" |

- `isEmpty()`：五字段全空。
- `renderBlock()`：非空字段渲染为 `- <Label>: <value>`。
- `parse(text)`：识别行首 `field:` 前缀（含别名），抽取到对应字段；首行非 `field:` 的内容作为目标 headline 返回；非识别前缀的冒号（如 "Fix bug: the parser"）**不动**。

---

## 5. 命令面 / Command Surface

### 5.1 `/goal` 子命令（扩展 `handleGoal`）

| 形式 | 行为 |
|---|---|
| `/goal` 或 `/goal status` | 打印 `statusLine()` |
| **`/goal show`** | status + 完整 contract block（**新增**） |
| **`/goal draft <objective>`** | aux LLM 起草 5 字段 contract，再 set goal（**新增**） |
| `/goal pause` | status → paused，清 wait 屏障 |
| `/goal resume` | status → active，`turnsUsed=0`，清屏障，触发 kickoff |
| `/goal clear` / **`stop`** / **`done`** | status → cleared（**新增 stop/done 别名**） |
| **`/goal wait <pid> [reason]`** | 设 pid 屏障（**新增**） |
| **`/goal unwait`** | 清 wait 屏障（**新增**） |
| `/goal <text>` | 解析 inline contract，set 新目标；支持 `--max-turns N`/`--max N` |

**mid-run 守卫**（对标仓库 gateway 行为）：Agent 运行中只允许控制类子命令（status/pause/resume/clear/stop/done/unwait/wait），设置新目标文本被拒："Agent 正在运行 —— 用 /goal status/pause/clear/wait，或先 /stop 再设新目标。"

### 5.2 `/subgoal` 子命令（**新增命令**）

| 形式 | 行为 |
|---|---|
| `/subgoal` | 列出当前 subgoals |
| `/subgoal <text>` | 追加一条准则 |
| `/subgoal remove <n>` | 删除第 n 条（1-based） |
| `/subgoal clear` | 清空全部 |

**注册**：`CommandRegistry` 新增 `register(core("subgoal", "agent", "管理当前目标的补充准则"))`；`GatewayCommandConstants` 新增 `COMMAND_SUBGOAL`/`SLASH_SUBGOAL`；`DefaultCommandService.handle` 新增 dispatch；新增 `handleSubgoal`。

---

## 6. 续轮提示模板 / Continuation Prompts

集中到 `GoalPromptTemplates`（常量），优先级 **contract > subgoals > plain**（对标仓库 `next_continuation_prompt`）：

- `CONTINUATION_PROMPT_TEMPLATE`（plain，已有，保留）
- **`CONTINUATION_PROMPT_WITH_CONTRACT_TEMPLATE`**（新增）：加 `Completion contract:\n{contract_block}` 段
- **`CONTINUATION_PROMPT_WITH_SUBGOALS_TEMPLATE`**（新增）：加 `Additional criteria the user added mid-loop:\n{subgoals_block}`
- contract + subgoals 同时存在时，subgoals 折叠进 contract block 作为 "Extra criterion N:" 行

---

## 7. Judge / Draft 提示模板

集中到 `GoalPromptTemplates`，内容与对标仓库逐字对齐（已确认为 load-bearing）：

- `JUDGE_SYSTEM_PROMPT`：定义 DONE/CONTINUE/WAIT 裁决契约，WAIT 需 `wait_on_pid`/`wait_for_seconds`
- `JUDGE_USER_PROMPT_TEMPLATE` / `_WITH_SUBGOALS_` / `_WITH_CONTRACT_`
- `DRAFT_CONTRACT_SYSTEM_PROMPT`：把裸目标转为 5 字段 JSON contract

---

## 8. 配置 / Config

`config.example.yml` 的 `solonclaw.goal` 段（**新增**，带中文注释）：

```yaml
solonclaw:
  goal:
    maxTurns: 20              # 默认轮次预算
    judgeTimeoutSeconds: 30   # judge 辅助调用超时
    judgeMaxTokens: 4096      # judge 最大 token
    judgeProvider: ""         # 留空则用会话默认 provider
    judgeModel: ""            # 留空则用会话默认 model
    maxConsecutiveParseFailures: 3  # 连续解析失败上限，超限自动暂停
```

`AppConfig` 新增内嵌 `GoalConfig`（镜像 `LearningConfig` 结构），默认值与上表一致。

---

## 9. 状态行与消息 / Status & Messages

`GoalService.statusLine()` 扩展（对标仓库 emoji + meta）：

- 无目标：`No active goal. Set one with /goal <text>.`
- active 等待中：`⏳ Goal (parked ...): <goal>`
- active：`⊙ Goal (active, N/M turns[, K subgoals][, contract]): <goal>`
- paused：`⏸ Goal (paused, N/M turns — <reason>): <goal>`
- done：`✓ Goal done (N/M turns): <goal>`

裁决/操作消息（打印给用户）：
- `⏳ Goal parked — waiting on <tgt>: <reason>`
- `✓ Goal achieved: <reason>`
- `⏸ Goal paused — judge model isn't returning valid JSON ...`
- `⏸ Goal paused — N/M turns used. Use /goal resume or /goal clear.`
- `↻ Continuing toward goal (N/M): <reason>`

---

## 10. 压缩迁移 / Migration on Compaction

新增 `GoalMigrationSupport.migrate(oldSessionId, newSessionId, reason)`：
1. 读父会话 `GoalState`，若不存在或非 active → 返回 false。
2. 子会话继承 active 目标（深拷贝），写库。
3. 父会话置 `status=cleared`，`pausedReason="migrated to " + newSessionId`，写库。
4. 返回 true。

调用点：`DefaultContextCompressionService` 在 session_id 轮转处（对标仓库 `conversation_compression.py:820`）。原地压缩（不改 session_id）不调用。

---

## 11. 错误处理 / Error Handling

- **judge 异常/超时**：fail-open → CONTINUE；不累计 `consecutiveParseFailures`。
- **judge JSON 不可解析**：累计 `consecutiveParseFailures`；达到上限（默认 3）→ 自动 `pause(pausedReason="judge parse failures")`。
- **budget 耗尽**：`turnsUsed >= maxTurns` → 自动 `pause(pausedReason="turn budget exhausted")`。
- **wait pid 无效**：`/goal wait <pid>` 校验 pid 存在，无效则拒绝。
- **mid-run 设新目标**：拒绝并提示用 `/stop`。
- 所有 goal 操作失败用 try/catch 包裹，记 warn 日志，不影响主对话链（与现有 `applyGoalDecision` 一致）。

---

## 12. 测试 / Testing

扩展现有 `CommandEnhancementTest.shouldSupportGoalCommandLifecycle`，并新增专项测试类（对标仓库 `test_goals.py` 子集）：

| 测试类 | 覆盖 |
|---|---|
| `GoalContractTest` | parse_contract 别名/多行/意外冒号；render_block；序列化往返 |
| `GoalStateSerializationTest` | 新字段往返；旧 JSON（9 字段）向前兼容加载 |
| `GoalJudgeTest` | LlmGoalJudge：done/continue/wait 裁决；空响应；JSON 解析失败；超时 fail-open |
| `GoalManagerSubgoalsTest` | add/remove/clear/persist |
| `GoalWaitBarrierTest` | pid 屏障设/自清/unwait；时间屏障过期；pause/resume 清屏障 |
| `GoalMigrationTest` | active 迁移/父归档；无目标返回 false；cleared 不迁移 |
| `GoalContinuationPromptTest` | contract > subgoals > plain 优先级；contract+subgoals 合并 |
| `GoalStatusLineTest` | 各状态 emoji + meta（含 subgoals/contract 计数） |

测试用真实 `LlmGateway` mock（Solon `@Inject` + Mockito），镜像 `AsyncSkillLearningService` 测试方式。

---

## 13. 文件改动清单 / File Changes

**新增**（`src/main/java/com/jimuqu/solon/claw/goal/`）：
- `GoalContract.java`
- `GoalJudgeRequest.java`, `GoalJudgeResult.java`
- `LlmGoalJudge.java`
- `GoalContractParser.java`
- `GoalContractDrafter.java`
- `GoalPromptTemplates.java`
- `GoalMigrationSupport.java`

**修改**（goal 包）：
- `GoalState.java`（+7 字段 + 序列化）
- `GoalVerdict.java`（+WAIT/WAITING/INACTIVE）
- `GoalJudge.java`（签名扩展为 `judge(GoalJudgeRequest)`）
- `GoalService.java`（subgoal/wait/unwait/contract/evaluateAfterTurn 重写）
- `HeuristicGoalJudge.java`（适配新接口签名，作为回退）

**修改**（命令/dispatch/网关）：
- `command/CommandRegistry.java`（注册 `subgoal`）
- `support/constants/GatewayCommandConstants.java`（`COMMAND_SUBGOAL`/`SLASH_SUBGOAL`）
- `gateway/command/DefaultCommandService.java`（`handleGoal` 扩展 + 新增 `handleSubgoal` + dispatch）
- `gateway/command/CommandValueSupport.java`（contract 解析辅助，如需）
- `core/model/GatewayMessage.java`（+`transient boolean goalContinuation` 标志）
- `gateway/service/DefaultGatewayService.java`（`safeScheduleGoalContinuation` 加抢占检查 + 续轮清理）
- `engine/DefaultConversationOrchestrator.java`（`applyGoalDecision` 透传新 metadata）
- `engine/DefaultContextCompressionService.java`（调用 goal 迁移）
- `bootstrap/GatewayConfiguration.java`（`goalService` bean 注入 `LlmGateway` + `GoalConfig`）
- `config/AppConfig.java`（+`GoalConfig` 内嵌类）
- `config.example.yml`（`solonclaw.goal` 段）
- `cli/LocalTerminalHelp.java`（/goal 帮助 + /subgoal）

**新增测试**（`src/test/java/com/jimuqu/solon/claw/goal/`）：见第 12 节。

---

## 14. 提交分块计划（5 个有效 commit）

1. **`feat: 目标状态模型扩展与契约支持 / Goal state model extension with contract support`** — `GoalState` 新字段、`GoalContract`、`GoalContractParser`、序列化兼容 + 测试。
2. **`feat: LLM 目标裁决器与辅助通道 / LLM goal judge with auxiliary channel`** — `LlmGoalJudge`、`GoalPromptTemplates`、`GoalConfig`、`AppConfig`、bean 接线 + 测试。
3. **`feat: 目标续轮循环与 wait 屏障 / Goal continuation loop with wait barriers`** — `GoalService.evaluateAfterTurn` 重写、`GoalMigrationSupport`、抢占检查、压缩迁移 + 测试。
4. **`feat: 目标与子目标命令面 / Goal and subgoal command surface`** — `/goal` 子命令扩展、`/subgoal`、`handleGoal`/`handleSubgoal`、help + 测试。
5. **`feat: 目标 draft 起草与状态行完善 / Goal draft contract and status line polish`** — `GoalContractDrafter`、status line、config.example.yml、文档 + 测试。

累计 5 次后提示用户合 `main`（merge commit 不计数）。

---

## 15. 风险与缓解

| 风险 | 缓解 |
|---|---|
| judge 同步调用拖慢回复 | 复用 auxiliary bounded executor + 30s 超时；fail-open 不阻塞 |
| 续轮死循环 | 轮次预算（默认 20）+ 连续解析失败自动暂停 + 用户可随时 pause/clear |
| 两个活跃目标（压缩后） | `GoalMigrationSupport` 父归档 + 状态机约束 |
| 抢占检查漏判 | 续轮消息打标记，`AgentRunSupervisor` 真实消息优先；兜底靠轮次预算 |
| 旧 JSON 不兼容 | `fromJson` 全字段带默认值，逐字段读取 |
