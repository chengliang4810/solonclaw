# sqlite-session-write-contention-hardening

## 标题
Harden shared SQLite session writes

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: false

## 分类
- category: Hermes 后端对齐 backlog

## Hermes 参考
- /Users/chengliang/code-repositories/hermes-agent/website/docs/developer-guide/session-storage.md 说明 Hermes 用 SQLite/WAL 持久化 sessions/messages，并带 FTS5、lineage、WAL checkpoint 和写入重试；这是当前项目最明显的持久化能力标杆。
- /Users/chengliang/code-repositories/hermes-agent/website/docs/developer-guide/provider-runtime.md 说明 Hermes 的 provider 解析是跨 CLI/gateway/cron 共享的，且有保存后的 provider/model 选择、fallback 链和 Anthropic/Codex 专用路径。
- /Users/chengliang/code-repositories/hermes-agent/website/docs/developer-guide/tools-runtime.md 说明 Hermes 的工具运行侧把工具注册、dispatch、异步桥接、agent-loop 工具和错误包装做成统一后端能力，而不仅是返回字符串。

## 当前项目观察
- /Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/config/RuntimeConfigResolver.java 和 /Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/support/RuntimeSettingsService.java 已经有可写 runtime/config.yml 与运行时配置白名单，配置侧比纯只读项目更接近 Hermes。
- /Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/gateway/service/GatewayRestartCoordinator.java、/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/gateway/service/GatewayRestartNotificationService.java、/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/engine/PendingSessionRecoveryService.java 已经覆盖了重启 drain、在线通知和近期 pending 会话自动恢复的骨架。
- /Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/storage/repository/SqliteSessionRepository.java 已经保存 session NDJSON、摘要与工具名/调用索引，/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/core/model/ToolResultEnvelope.java 也已有 result_ref/preview/truncated 字段，但还没有 Hermes 那种完整的工具结果落盘与可恢复链路。

## 当前缺口
Hermes 的会话库在写入侧有明确的并发保护策略（短超时、抖动重试、BEGIN IMMEDIATE、周期性 WAL checkpoint）。当前项目的 SqliteSessionRepository 主要是普通 JDBC 写入，面对 gateway/CLI/恢复任务并发时更容易出现锁等待或写失败。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/storage/repository/SqliteSessionRepository.java`

## 验证方式
并发压测 save/update/search，验证锁冲突时能自动重试且不会丢 session 更新；重启后会话仍可正常读取。

