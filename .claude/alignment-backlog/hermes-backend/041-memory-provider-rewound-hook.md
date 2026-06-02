# 041-memory-provider-rewound-hook

## 标题
Memory provider session rewind notification

## 状态
- status: queued

## 优先级 / 风险
- priority: high
- risk: medium
- parallelSafe: false
- overlapHint: `041-memory-provider-rewound-hook`

## 对标能力
来源领域：memory-skills-backend-alignment

## 当前缺口
Hermes distinguishes ordinary session switches from transcript rewinds so providers can invalidate cached turn state; the current Java API has only start/prefetch/sync hooks, so a rewind can leave an external memory backend pointing at the wrong turn history.

## Hermes 证据
- `/Users/chengliang/code-repositories/hermes-agent/agent/memory_provider.py:175-217 (on_session_switch(..., rewound=True))`
- `/Users/chengliang/code-repositories/hermes-agent/agent/memory_manager.py:494-521 (forwards rewound only when transcript is truncated)`

## 目标文件
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/core/service/MemoryProvider.java:6-26`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/context/DefaultMemoryManager.java:61-109`
- `/Users/chengliang/code-projects/jimuqu-agent/src/main/java/com/jimuqu/solon/claw/context/MemoryContextBoundary.java:1-200`

## 验证方式
Undo/rewind path sends rewound=true exactly once; resume/branch paths do not add rewound metadata; older providers keep working without code changes.
