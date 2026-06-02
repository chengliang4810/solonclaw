# 032-approval-timeout-denied-outcome

## 标题
结构化审批超时与拒绝结果

## 状态
- status: selected

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: true
- overlapHint: `032-approval-timeout-denied-outcome`

## 对标能力
来源领域：backend parity analysis for cron / approval / security

## 当前缺口
Hermes treats timeout and deny as a first-class non-approval outcome instead of only an absence of confirmation. That distinction matters for downstream logic and audits, and it is the cleanest way to prevent ambiguous 'maybe approved' states from propagating across tool calls.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/tests/gateway/test_approve_deny_commands.py:timeout/deny flow`
- `/Users/chengliang/code-repositories/hermes-agent/acp_adapter/edit_approval.py:requester timeout returns False`
- `/Users/chengliang/code-repositories/hermes-agent/tests/acp/test_edit_approval.py`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/tool/runtime/DangerousCommandApprovalService.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/tool/runtime/ApprovalAuditObserver.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/test/java/com/jimuqu/solon/claw/DangerousCommandApprovalServiceTest.java`

## 验证方式
Extend approval tests so timeout and deny both return approved=false, while audit records preserve a distinct outcome/status field for timeout versus explicit denial.
