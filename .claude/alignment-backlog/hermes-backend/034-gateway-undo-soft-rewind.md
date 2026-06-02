# 034-gateway-undo-soft-rewind

## 标题
Use soft-delete rewind for undo

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: false
- overlapHint: `034-gateway-undo-soft-rewind`

## 对标能力
来源领域：backend alignment

## 当前缺口
Hermes supports /undo as a soft rewind that marks truncated rows inactive for audit, while the current project still looks like it only advertises undo at the command layer and has no obvious backend rewind/soft-delete flow. This is a good alignment gap because it is self-contained in session persistence and command handling.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/gateway/session.py:1312-1362`
- `/Users/chengliang/code-repositories/hermes-agent/gateway/session.py:1288-1295`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/support/MessageSupport.java:66`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/command/CommandRegistry.java:24`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java`

## 验证方式
Add backend tests for default rewind 1 turn, rewind N turns, empty-session no-op, and inactive audit visibility without hard transcript replacement.
