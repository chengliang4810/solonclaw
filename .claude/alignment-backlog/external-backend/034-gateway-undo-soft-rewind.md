# 034-gateway-undo-soft-rewind

## 标题
使用软删除语义回退会话 turn

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: false

## 对标能力
对标实现支持按 user turn 回退，并保留 inactive 审计视图而不是硬改历史。

## 当前缺口
当前有会话持久化和 slash command 基础，但还缺少按 turn 软回退与审计保留的统一后端语义。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/core/repository/SessionRepository.java`
- `src/main/java/com/jimuqu/solon/claw/storage/repository/SqliteSessionRepository.java`
- `src/main/java/com/jimuqu/solon/claw/engine/DefaultCommandService.java`

## 验证方式
新增 undo rewind 测试，覆盖默认回退 1 turn、回退 N turn、空会话 no-op 与 inactive 审计。
