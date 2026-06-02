# 036-restart-notification-marker

## 标题
Persist restart requester marker and send one-time recovery notice

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: false
- overlapHint: `036-restart-notification-marker`

## 对标能力
来源领域：backend alignment

## 当前缺口
Hermes persists restart requester routing, then emits a one-time post-restart notification back to the originating session. The current project already writes a restart-requester marker and has a notification service, but the flow still looks less explicit about one-shot delivery semantics and marker lifecycle than Hermes.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/gateway/run.py:3572-3743`
- `/Users/chengliang/code-repositories/hermes-agent/gateway/status.py:846-994`
- `/Users/chengliang/code-repositories/hermes-agent/gateway/shutdown_forensics.py:163-190`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/gateway/service/GatewayRestartCoordinator.java:143-169`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/gateway/service/GatewayRestartNotificationService.java:37-88`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/bootstrap/GatewayConfiguration.java:290`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java:1598-1657`

## 验证方式
Confirm requester platform/chat/thread metadata is saved, startup delivers exactly once, and missing or invalid markers do not send notifications.
