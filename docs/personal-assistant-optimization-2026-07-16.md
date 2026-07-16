# 个人型 AI 助手优化建议（2026-07-16）

> **背景**：亮哥的目标是把当前项目做成**个人型 AI 助手**——不考虑多租户/多人使用，减少使用过程中的确认授权操作，并具备自我总结和反思能力。本次审计基于真实代码（approval 流程、self-reflection/memory 机制、persona md 加载机制、多租户残留排查）。
>
> 审批流（approval-audit）子任务因推理网关超时未完整跑完，相关结论从其余报告中的交叉信息补充推断，标注为「推断」。

---

## 一、减少授权确认操作

### 现状（部分为推断）

- Memory 工具写入 `MEMORY.md` / `USER.md` 时走 `MemoryApprovalCoordinator` 审批（`tool/runtime/MemoryTools.java`、`FileMemoryService.java` 引用）
- Shell/文件类工具存在安全审批策略（`SecurityPolicy` 相关类，命名依据 CLAUDE.md 中"审批/安全策略"描述）
- 这套机制设计上是为多人/高风险场景兜底的，对纯个人单机部署而言摩擦偏高

### 优化方向

1. **新增 `agent.personal-mode` 配置项**，开启后：
   - Memory 工具操作（`MemoryTools.java`）直接执行，跳过 `MemoryApprovalCoordinator`
   - `AsyncSkillLearningService` 写 `SKILL.md` 不需要确认
   - 文件读写限定在 `workspace/` 以内时自动放行

2. **工具级信任分级**：区分"低风险工具（文件读、记忆读写、Web fetch）"和"高风险工具（Shell 执行、系统命令）"。个人模式下前者全自动，后者保留单次确认但支持"记住这次选择"。

3. **渠道绑定后免认证**：配对完成后的主人消息应零摩擦。`GatewayAuthorizationService.isAuthorized()` 本身已是单人判断（`sameUser(admin.userId, message.userId)`），如果 pairing 流程本身还有交互式确认步骤，可提供命令行直接注入已知 userId、跳过交互式配对。

---

## 二、自我反思与总结机制

### 现状（已确认，来自代码审计）

| 机制 | 文件 | 触发方式 | 跨会话影响 |
|------|------|----------|-----------|
| 上下文压缩 | `engine/DefaultContextCompressionService.java` | 被动·token 超阈值才触发（`compressIfNeeded` L66）| ❌ 无，仅影响当前 session |
| Memory 工具 | `tool/runtime/MemoryTools.java` L70 | Agent 主动调用 | ✅ 写入内容注入后续 system prompt |
| 异步技能学习 | `context/AsyncSkillLearningService.java` | 每次回复后（`schedulePostReplyLearning` L126），tool 调用数达阈值或有近期 checkpoint | ✅ 更新 `MEMORY.md` / `SKILL.md` |
| Skill Curator | `context/SkillCuratorService.java` + `scheduler/SkillCuratorScheduler.java` | 定时（默认 1h）+ 空闲门控（`isIdleEnough` L85）| ⚠️ 仅打健康标签（active/stale/archived），不提炼内容 |
| Heartbeat 调度 | `scheduler/HeartbeatScheduler.java` L89 | 定时，读 `HEARTBEAT.md` checklist，走完整 `conversationOrchestrator.runScheduled()` | 间接——取决于 HEARTBEAT.md 是否配置了自省指令 |

### 核心差距

> "因上下文满而压缩" vs "定期主动回顾近期会话、更新自我知识"

- **前者已存在**：`DefaultContextCompressionService`，完全被动，只处理当前 session 的 token 压力。
- **后者部分存在**：`AsyncSkillLearningService` 是事件驱动的（"本轮 tool 数够多"），**不是定期批量回顾历史会话**。目前没有一个独立定时 job 能在空闲时批量扫描最近 N 个 session、归纳跨会话规律、主动更新记忆/技能。Curator 是最接近的定时机制，但只做健康标注，不做内容提炼。

### 建议补充

1. **`ReflectionScheduler`（新建，或直接复用 Heartbeat）**：在 `HEARTBEAT.md` 默认模板里加入反思 checklist（例如："回顾最近 7 天的会话，归纳用户偏好变化、常见任务模式，更新 MEMORY.md"）。复用现有的 `conversationOrchestrator.runScheduled()` 走完整 Agent 循环，可调用 Memory 工具写回。**这一步不需要新建类，配置好 HEARTBEAT.md 模板就能跑起来**，是成本最低的第一步。

2. **每日记忆滚动压缩**：`memory/YYYY-MM-DD.md` 文件会随时间持续堆积，可能撑爆 system prompt 预算（当前总预算 48000 chars）。建议新增 `MemoryCompactionJob`：每天凌晨把 7 天前的 today 文件提炼进 `MEMORY.md` 后删除原文件。

3. **提升 `SkillCuratorService` 能力**：现在只做健康标注，可以在标记为 stale 的技能上触发一次 LLM 评估——"这个技能还有用吗？是否可以合并进更通用的技能？"，让技能库越用越精炼而不是越堆越臃肿。

---

## 三、Persona 加载机制（现状已经很好，小优化即可）

### 现状（已确认，来自代码审计）

所有受控 persona 文件统一定义在 `support/constants/ContextFileConstants.java`，运行时放在 `workspace/` 根目录：

| Key | 文件名 | 用途 |
|---|---|---|
| `agents` | `AGENTS.md` | 工作区规则（最高优先级） |
| `soul` | `SOUL.md` | 角色灵魂 |
| `identity` | `IDENTITY.md` | 角色身份 |
| `user` | `USER.md` | 用户画像 |
| `tools` | `TOOLS.md` | 工具使用规范 |
| `heartbeat` | `HEARTBEAT.md` | 心跳规则 |
| `memory` | `MEMORY.md` | 长期记忆主文件 |
| `bootstrap` | `BOOTSTRAP.md` | 新工作区首次引导 |
| `memory_today` | `memory/YYYY-MM-DD.md` | 当日流水记忆 |

**数据流**：`workspace/*.md` → `PersonaWorkspaceService.readPromptBody()` → `FileContextService.appendWorkspaceFile()` / `buildSystemPrompt()` → 依次拼接 Workspace Rules / Project Context / Agent Block / Memory Block / Bootstrap-Soul-Tools-Identity-User / Personality / Skill Index → `truncateToTotalBudget()`（单文件 12000 chars，总预算 48000 chars）→ 注入 LLM 调用（`DefaultConversationOrchestrator.java:508,753`）。

**加载粒度**：per-session，每次 LLM 调用前实时读盘，无缓存，改完立即生效。

**自写回支持**：已支持，机制完整，对标 Claude Code 的 memory 工具——`MemoryTools.java` 暴露 add/replace/remove/read，`FileMemoryService.java` 负责实际读写（含文件锁 + 原子移动），并有注入防护扫描和前台审批流。

**与外部对标实现对比**：高度对齐，甚至更完整（多了 SOUL/TOOLS/HEARTBEAT 等细化文件、子 Agent scope 隔离、群聊访客隐私隔离）。

### 小优化建议

1. **新增 `REFLECTION.md` 槽位**：与 `MEMORY.md` 分开。`MEMORY.md` 存"事实性记忆"，`REFLECTION.md` 存"自我认知/规律总结"，在 system prompt 里作为独立 section 注入，对 LLM 的可读性和调用质量更好。

2. **system prompt 预算分配优化**：当前单文件上限 12000 chars、总预算 48000 chars，对重度使用用户（`MEMORY.md` 持续增长）容易触发截断。可为 `MEMORY.md` 设置独立/动态上限，静态文件（SOUL/IDENTITY 等）的压缩优先级应低于持续增长的记忆文件。

---

## 四、Profile 多租户残留清理（最大的技术债）

### 现状（已确认，来自代码审计）

CLAUDE.md 明确写着"已确认不做：...Profiles、多实例/多租户/多机器人隔离"，但代码库里存在约 **35+ 文件**的完整多 Profile 子容器体系：

| 类别 | 文件（节选） | 数量 |
|------|------|------|
| `profile/` 整包 | ProfileManager / ProfileBootstrap / ProfileMultiplexProfiles / ProfileRuntimeScope / ProfileRuntimeIdentity / ProfileEnvironmentLoader / ProfileBeanResolver / ProfileChildRuntimeMarker / ProfileArchive / ProfileGatewayMultiplexGuard 等 | ~20 |
| `gateway/service/` 多路复用层 | ProfileMessageRouter / ProfileMultiplexRuntimeManager / ProfileRuntimeBundle / ProfileRuntimeBundleFactory | 4 |
| `web/` Profile Dashboard | DashboardProfileController / DashboardProfileService / DashboardProfileScope / AbstractDashboardProfileController / DashboardProfileGatewayClient / DashboardProfileContext / DashboardProfileConfigFile | 7 |
| `bootstrap/ProfileRuntimeSupportConfiguration` | 命名 Profile 子容器的 Solon Bean 装配配置 | 1 |
| `storage/repository/ReadOnlyProfileSessionRepository` | 跨 Profile 会话只读查询 | 1 |
| 字段污染 | `GatewayMessage.profile`（每条消息带路由字段，影响 session key 生成）、`SessionSearchQuery.profile`、`SessionSearchEntry.profile`、`DeliveryRequest.profile` | — |
| 用户可见文本泄漏 | `GatewayAuthorizationService.java:311` pairing 提示语硬编码"当前 Profile 尚未绑定你的..." | — |

### 有意为之、无需改动的单主人设计（作为对照）

| 组件 | 判断 |
|------|------|
| `DashboardAuthService`（单 Bearer token 鉴权，无用户表） | ✅ 正确的单所有者设计 |
| `PlatformAdminRecord.userId` + `GatewayAuthorizationService.isAuthorized()` | ✅ 单管理员 pairing 逻辑干净 |
| `GatewayMessage.userId` | ✅ 必要的发送者身份字段 |
| `AgentProfile`（角色配置，无 tenantId） | ✅ 设计干净 |
| `ApprovedUserRecord` / `PairingRateLimitRecord` | ✅ 单主人配对流程辅助表 |
| `SessionRepository.userId` 参数 | ✅ 群聊场景区分主人/访客消息，非多租户 scope |

### 建议

Profile 概念不仅是死代码，还通过 `GatewayMessage.profile` 字段渗入消息路由核心路径，会持续制造认知混乱和维护负担。**建议作为一个专门的清理 PR 来做**：从最独立的 `profile/` 包和 `gateway/service/ProfileMultiplex*` 开始评估，逐步向外清理字段污染，影响面较大但改动边界清晰。

---

## 优先级汇总

```
P0  Profile 整包清理（消除最大技术债和认知混乱）
P1  personal-mode 配置 + MemoryApprovalCoordinator 旁路（减少授权确认）
P1  HEARTBEAT.md 模板加入反思 checklist（零新代码，复用现有调度器）
P2  today memory 滚动压缩 job（防止 system prompt 撑爆）
P2  REFLECTION.md 作为独立反思输出槽
P3  SkillCuratorService 升级：stale 技能触发 LLM 评估合并
```
