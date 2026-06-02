# 035-gateway-shutdown-drain

## 标题
Drain active runs before shutdown interrupt

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: high
- parallelSafe: false
- overlapHint: `035-gateway-shutdown-drain`

## 对标能力
来源领域：backend alignment

## 当前缺口
Hermes drains active runs before interrupting, then performs best-effort shutdown notifications and cleanup in a fixed order. The current project has restart drain coordination, but the shutdown boundary still appears split across restart coordination, run control, and process cleanup without a clearly unified drain-then-interrupt shutdown path.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/gateway/run.py:3532-3560`
- `/Users/chengliang/code-repositories/hermes-agent/gateway/run.py:3562-3570`
- `/Users/chengliang/code-repositories/hermes-agent/gateway/run.py:3745-3750`
- `/Users/chengliang/code-repositories/hermes-agent/gateway/platforms/whatsapp.py:774-799`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/gateway/service/GatewayRestartCoordinator.java:69-123`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/engine/AgentRunSupervisor.java:208-213`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/tool/runtime/ProcessRegistry.java`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/gateway/service/*`

## 验证方式
Verify normal shutdown waits for active runs to finish, timeout triggers interruption, and tool processes are cleaned before adapter disconnect or final teardown.
