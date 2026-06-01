# session-lineage-and-export-apis

## 标题
Add session lineage and export helpers

## 状态
- status: selected

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true

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
Hermes 把 parent_session_id lineage、会话导出和清理当作一等后端能力，方便审计、追踪与恢复。当前项目虽然记录了 parentSessionId/title/ndjson，但还缺少专门的 lineage 查询、导出与 prune 接口，后端排障和数据迁移不够顺手。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/core/model/SessionRecord.java`
- `src/main/java/com/jimuqu/solon/claw/core/repository/SessionRepository.java`
- `src/main/java/com/jimuqu/solon/claw/storage/repository/SqliteSessionRepository.java`

## 验证方式
新增 ancestry/descendant 查询与导出测试，确认 lineage 顺序正确、导出内容可重放、prune 不会误删未结束会话。

