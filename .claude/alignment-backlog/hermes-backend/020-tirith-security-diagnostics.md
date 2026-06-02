# 020-tirith-security-diagnostics

## 标题
对齐 Tirith 安全诊断与审计面

## 状态
- status: done
- completedCommit: `c9717331`

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true
- overlapHint: `020-tirith-security-diagnostics`

## 对标能力
来源领域：backend parity analysis for cron / approval / security

## 当前缺口
Hermes exposes security scanning through a dedicated audit/doctor surface with explicit failure-mode reporting, while the current project's Tirith wrapper is already solid but can still be made more operationally transparent. This is a lower-risk, low-conflict gap and a good fit once the approval/session items are handled.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/hermes_cli/security_audit.py`
- `/Users/chengliang/code-repositories/hermes-agent/hermes_cli/doctor.py`
- `/Users/chengliang/code-repositories/hermes-agent/tests/cli/test_security_audit.py`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/tool/runtime/TirithSecurityService.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/tool/runtime/SecurityPolicyService.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/test/java/com/jimuqu/solon/claw/TirithSecurityServiceTest.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/test/java/com/jimuqu/solon/claw/SecurityPolicyServiceTest.java`

## 验证方式
Assert diagnose/policySummary expose scanner state, fail-open/closed behavior, timeout handling, and redacted fields; add tests for a stable audit summary that does not leak raw paths or findings.
