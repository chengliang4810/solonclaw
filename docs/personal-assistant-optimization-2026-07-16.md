# 个人型 AI 助手架构说明

> 当前产品定位是单所有者个人助手。Profile 用于拆分工作区、模型、渠道和工具权限，`SOUL.md` 是唯一人格来源。

---

## 一、安全与审批边界

项目不提供绕过安全边界的个人模式总开关。个人助手身份由渠道管理员绑定确认，文件写入、Shell 命令和其他有副作用的工具继续经过统一的安全策略、审批和审计链。

- 工作区内外的文件副作用都按现行策略判定，不因 Profile 或渠道改变规则。
- 危险命令必须产生可追踪的待审批记录；一次、会话和永久授权只影响明确匹配的规则。
- 配对完成后的主人消息直接进入对应 Profile；群聊访客使用隔离上下文，不能读取主人的历史和记忆。

---

## 二、反思、记忆与技能维护

现行实现包含以下互相独立的链路：

- 上下文压缩只处理当前会话的 token 压力。
- `MEMORY.md`、`USER.md`、每日记忆和专题记忆组成长期记忆，并通过统一检索进入模型上下文。
- 跨会话反思写入独立的 `REFLECTION.md`，不与事实记忆混写。
- 每日记忆归档保留原文并生成可检索摘要，不把历史数据静默删除。
- 技能学习和 Curator 分别负责会话后学习与周期性整理，使用统一的技能统计。

---

## 三、SOUL 与工作区上下文加载机制

所有受控上下文文件统一定义在 `support/constants/ContextFileConstants.java`，运行时放在 `workspace/` 根目录：

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

**数据流**：`workspace/*.md` → `PersonaWorkspaceService` → `FileContextService.buildSystemPrompt()` → 依次拼接工作区规则、项目上下文、记忆、`BOOTSTRAP.md`、`SOUL.md`、`TOOLS.md`、`IDENTITY.md`、`USER.md`、技能索引和反思 → 按预算裁剪 → 注入模型调用。

**加载粒度**：per-session，每次 LLM 调用前实时读盘，无缓存，改完立即生效。

**自写回支持**：已支持，机制完整，对标 Claude Code 的 memory 工具——`MemoryTools.java` 暴露 add/replace/remove/read，`FileMemoryService.java` 负责实际读写（含文件锁 + 原子移动），并有注入防护扫描和前台审批流。

`SOUL.md` 是唯一人格来源。`IDENTITY.md` 只保存身份事实，`AGENTS.md` 只保存工作规则，Profile 和子代理运行范围不再注入额外角色提示词。

---

## 四、Profile 与 SOUL.md

Profile 负责独立工作区、模型、渠道和工具权限边界。每个 Profile 的人格只由其工作区内的 `SOUL.md` 定义；`IDENTITY.md` 只保存名称、形态、Emoji 和头像等身份信息。

Profile、子代理和群聊访客隔离只负责运行范围与上下文边界，不提供额外的人格来源。
