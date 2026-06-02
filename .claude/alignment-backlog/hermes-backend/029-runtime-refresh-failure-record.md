# 029-runtime-refresh-failure-record

## 标题
Persist the latest runtime refresh failure

## 状态
- status: queued

## 优先级 / 风险
- priority: medium
- risk: low
- parallelSafe: true
- overlapHint: `029-runtime-refresh-failure-record`

## 对标能力
来源领域：backend runtime/config/doctor alignment

## 当前缺口
Refresh failures currently return a result and log, but they do not persist a last-failure record for status/health/doctor consumers. Hermes uses structured runtime state to keep failure context visible beyond the immediate call path.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/gateway/status.py:549 (runtime status reader used by health/status surfaces)`
- `/Users/chengliang/code-repositories/hermes-agent/hermes_cli/web_server.py:674-695 (runtime status consulted as part of health handling)`
- `/Users/chengliang/code-repositories/hermes-agent/tests/hermes_cli/test_gateway_runtime_health.py:1-23 (runtime health lines surface fatal platform and startup issue)`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/gateway/service/GatewayRuntimeRefreshService.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/web/DashboardStatusService.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java`

## 验证方式
Force an invalid runtime config reload and confirm the last failure is retained in a structured status field with redacted error text.
