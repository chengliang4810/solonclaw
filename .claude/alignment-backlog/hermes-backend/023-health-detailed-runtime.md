# 023-health-detailed-runtime

## 标题
Expand detailed health with richer runtime snapshot

## 状态
- status: done
- completedCommit: `32a0b47f`

## 优先级 / 风险
- priority: high
- risk: low
- parallelSafe: true
- overlapHint: `023-health-detailed-runtime`

## 对标能力
来源领域：backend runtime/config/doctor alignment

## 当前缺口
Current /health/detailed already emits uptime and timestamps, but Hermes /health/detailed also carries gateway status and runtime state fields that downstream callers can use for lightweight diagnosis without invoking doctor.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/hermes_cli/web_server.py:593-632 (_probe_gateway_health uses /health/detailed first and falls back to /health)`
- `/Users/chengliang/code-repositories/hermes-agent/tests/gateway/test_api_server.py:517-555 (GET /health/detailed returns status, platform, gateway_state, platforms, active_agents, pid, updated_at)`
- `/Users/chengliang/code-repositories/hermes-agent/gateway/status.py:549 (read_runtime_status)`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/bootstrap/HealthController.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/web/DashboardStatusService.java`

## 验证方式
Assert /health/detailed includes runtime summary plus gateway/status fields, with stable JSON keys when runtime state is missing.
