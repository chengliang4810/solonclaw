# 033-approval-context-session-isolation

## 标题
绑定审批上下文避免并发串线

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: true
- overlapHint: `033-approval-context-session-isolation`

## 对标能力
来源领域：backend parity analysis for cron / approval / security

## 当前缺口
Hermes binds approval state to a session-scoped execution context so one blocked run cannot be accidentally approved or denied by another. The current project has session-aware approval plumbing, but this is still a likely parity gap whenever concurrent sessions or nested runs compete for the same approval state.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/acp_adapter/edit_approval.py:_EDIT_APPROVAL_REQUESTER ContextVar`
- `/Users/chengliang/code-repositories/hermes-agent/tests/acp/test_approval_isolation.py`
- `/Users/chengliang/code-repositories/hermes-agent/tests/gateway/test_approve_deny_commands.py:parallel approval queue`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/tool/runtime/DangerousCommandApprovalService.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/core/model/AgentRunContext.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/test/java/com/jimuqu/solon/claw/DangerousCommandApprovalServiceTest.java`

## 验证方式
Add a concurrent two-session regression proving A's /approve or approval callback cannot resolve B's pending command, and that stale approval bindings are cleared when a run ends.
