# 033-approval-context-session-isolation

## 标题
绑定审批上下文避免并发串线

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: true

## 对标能力
对标实现把审批上下文绑定到会话运行上下文，避免并发会话共享错误的 pending approval。

## 当前缺口
当前 Java 侧可用 ThreadLocal 或 run context 做隔离，但仍需要覆盖 gateway、ACP、Cron 等入口的并发审批边界。

## 目标文件
- `src/main/java/com/jimuqu/solon/claw/tool/runtime/DangerousCommandApprovalService.java`
- `src/main/java/com/jimuqu/solon/claw/engine/AgentRunContext.java`

## 验证方式
两个 session 同时触发危险命令时，A 的 approve 不批准 B；Cron 不进入交互审批等待。
